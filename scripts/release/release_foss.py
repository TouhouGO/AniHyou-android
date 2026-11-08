#!/usr/bin/env python3
"""Validate, sign and publish a versioned FOSS release without using devices."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from apk_artifacts import record_build, sign_and_verify, verify_artifacts

ROOT = Path(__file__).resolve().parents[2]


def run(args, *, cwd=ROOT, input=None):
    result = subprocess.run(args, cwd=cwd, input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} operation failed; no release was confirmed")
    return result.stdout.strip()


def version_properties(root):
    return dict((key.strip(), value.strip())
                for key, value in (line.split("=", 1)
                                   for line in (root / "version.properties").read_text().splitlines()
                                   if "=" in line))


def validate(version, head, root=ROOT):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Invalid release version or source commit")
    props = version_properties(root)
    if props.get("name") != version or int(props.get("code", "0")) <= 0:
        raise ValueError("Requested version does not match version.properties")
    if run(["git", "rev-parse", "HEAD"], cwd=root) != head:
        raise ValueError("Checkout does not match the requested source commit")
    if run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root):
        raise ValueError("Release checkout has uncommitted changes")


def validate_graph(text):
    tasks = [line for line in text.splitlines() if line.startswith(":")]
    if "BUILD SUCCESSFUL" not in text or not tasks or not any(
        line.startswith(":app:assembleFossRelease ") for line in tasks
    ):
        raise ValueError("Release task graph is incomplete")
    forbidden = re.compile(
        r"connected|manageddevice|devicecheck|androidtest|install|uninstall|generate.*baselineprofile",
        re.IGNORECASE,
    )
    if any(forbidden.search(line) for line in tasks):
        raise ValueError("Release graph contains a device or profile-generation task")
    return len(tasks)


def repository(root=ROOT):
    remote = run(["git", "remote", "get-url", "origin"], cwd=root)
    match = re.fullmatch(r"https://github\.com/([A-Za-z0-9-]+/AniHyou-android)(?:\.git)?", remote)
    if not match:
        raise ValueError("Unexpected release repository remote")
    sys.path.insert(0, str(root / "scripts/localization"))
    from publish_catalog import REPO
    if match[1] != REPO:
        raise ValueError("Release remote differs from the maintained fork")
    return REPO


def build(version, head, root=ROOT):
    validate(version, head, root)
    repository(root)
    checkout_tree = run(["git", "rev-parse", "HEAD^{tree}"], cwd=root)
    command = ["bash", str(ROOT / "scripts/ci/gradle-with-network-retry.sh"), ":app:assembleFossRelease",
               "--no-configuration-cache", "--no-daemon", "--console=plain", "--max-workers=2"]
    build_env = {key: value for key, value in os.environ.items()
                 if key not in {"KEYSTORE_B64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "GH_TOKEN", "GITHUB_TOKEN"}}
    graph = subprocess.run(command + ["--dry-run"], cwd=root, env=build_env, text=True, capture_output=True)
    if graph.returncode:
        raise RuntimeError("Release task-graph calculation failed")
    print(f"Verified {validate_graph(graph.stdout)} host-only release tasks", flush=True)
    validate(version, head, root)
    subprocess.run(command, cwd=root, env=build_env, check=True)
    validate(version, head, root)
    if run(["git", "rev-parse", "HEAD^{tree}"], cwd=root) != checkout_tree:
        raise ValueError("Source tree changed during the release build")
    record_build(root / "app/build/outputs/apk/foss/release", root)
    print("Verified release build receipt recorded")


def find_release(repo, tag):
    matches = []
    for page in range(1, 21):
        rows = json.loads(run(["gh", "api", f"repos/{repo}/releases?per_page=100&page={page}"]))
        if not isinstance(rows, list) or len(rows) > 100:
            raise ValueError("Invalid release list")
        matches.extend(row for row in rows if row.get("tag_name") == tag)
        if len(matches) > 1:
            raise ValueError("Duplicate release tag")
        if len(rows) < 100:
            return matches[0] if matches else None
    raise ValueError("Release listing exceeds the verification limit")


def tag_commit(repo, tag):
    refs = run(["git", "ls-remote", "--tags", f"https://github.com/{repo}.git",
                f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"])
    if not refs:
        return None
    values = dict(line.split()[1::-1] for line in refs.splitlines())
    return values.get(f"refs/tags/{tag}^{{}}", values.get(f"refs/tags/{tag}"))


def release_notes(version, manifest):
    return f"""AniHyou 中文增强版 {version}（FOSS）

- 中文标题、人物和标签采用独立显示层，保留原始 AniList 数据。
- 支持中文别名与繁简搜索、完整候选分页，以及渐进式在线补充。
- 资源支持前台自动检查、手动下载、导入和恢复；不可用时继续使用内置目录。
- 保留官方上游同步；新增提交使用维护账号的 GitHub 隐私邮箱。

优先下载 universal APK，或选择设备对应架构。包名 `{manifest['packageName']}`，版本代码 `{manifest['versionCode']}`。
签名证书与此前 1.7.1 一致。发布工具已检查资源、baseline profile、包信息、签名和构建记录；未进行设备安装或运行验证。

SHA256SUMS 与 release-manifest.json 提供文件哈希、源码提交及签名证书摘要。
"""


def verify_remote_assets(repo, release, artifacts, subset=False):
    files = {p.name: p for p in artifacts.iterdir() if p.is_file()}
    names = [asset["name"] for asset in release["assets"]]
    if len(names) != len(set(names)) or not set(names).issubset(files) or (not subset and set(names) != set(files)):
        raise ValueError("Published asset set differs from verified artifacts")
    for asset in release["assets"]:
        path = files[asset["name"]]
        if asset["size"] != path.stat().st_size:
            raise ValueError("Remote asset size mismatch")
        result = subprocess.run(
            ["gh", "api", "-H", "Accept: application/octet-stream",
             f"repos/{repo}/releases/assets/{asset['id']}"], capture_output=True,
        )
        if result.returncode or hashlib.sha256(result.stdout).digest() != hashlib.sha256(path.read_bytes()).digest():
            raise ValueError("Remote release asset verification failed")


def publish(artifacts, head, version, prerelease, root=ROOT):
    artifacts = Path(artifacts).resolve()
    validate(version, head, root)
    manifest = verify_artifacts(artifacts, root)
    if manifest["sourceCommit"] != head:
        raise ValueError("Artifacts are from another source commit")
    repo, tag = repository(root), "v" + version
    ref = tag_commit(repo, tag)
    if ref is not None and ref != head:
        raise ValueError("Existing tag points to another source commit")
    release = find_release(repo, tag)
    if release and release["prerelease"] != prerelease:
        raise ValueError("Existing release type differs")
    if release and release["draft"] and release["target_commitish"] != head:
        raise ValueError("Existing draft targets another source commit")
    if release and not release["draft"] and ref != head:
        raise ValueError("Published release tag cannot be verified")
    files = {p.name: p for p in artifacts.iterdir() if p.is_file()}
    if release is None:
        with tempfile.TemporaryDirectory(prefix="anihyou-release-") as directory:
            notes = Path(directory) / "notes.md"
            notes.write_text(release_notes(version, manifest))
            args = ["gh", "release", "create", tag, "--repo", repo, "--draft",
                    "--target", head, "--title", f"AniHyou 中文增强版 v{version}",
                    "--notes-file", str(notes), "--latest=false"]
            if prerelease:
                args.append("--prerelease")
            run(args)
        release = find_release(repo, tag)
        if release is None:
            raise ValueError("Created draft could not be verified")
    known = [asset["name"] for asset in release["assets"]]
    if len(known) != len(set(known)) or not set(known).issubset(files):
        raise ValueError("Existing release contains unexpected assets")
    verify_remote_assets(repo, release, artifacts, subset=True)
    # A retry can resume a draft, but never replace an existing asset or release.
    if release["draft"]:
        existing = {asset["name"]: asset for asset in release["assets"]}
        for name, path in files.items():
            if name not in existing:
                run(["gh", "release", "upload", tag, str(path), "--repo", repo])
        release = find_release(repo, tag)
    verify_remote_assets(repo, release, artifacts)
    if release["draft"]:
        ref = tag_commit(repo, tag)
        if ref is not None and ref != head:
            raise ValueError("Tag changed before publication; draft retained")
        run(["gh", "release", "edit", tag, "--repo", repo, "--draft=false",
             "--latest=false" if prerelease else "--latest"])
    release = find_release(repo, tag)
    if release is None or release["draft"] or tag_commit(repo, tag) != head:
        raise ValueError("Final release state could not be verified")
    verify_remote_assets(repo, release, artifacts)
    print(f"Published https://github.com/{repo}/releases/tag/{tag}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("validate")
    check.add_argument("--version", required=True)
    check.add_argument("--head", required=True)
    check.add_argument("--source-root", type=Path, default=ROOT)
    graph = commands.add_parser("graph")
    graph.add_argument("--file", type=Path, required=True)
    assemble = commands.add_parser("build")
    assemble.add_argument("--version", required=True)
    assemble.add_argument("--head", required=True)
    assemble.add_argument("--source-root", type=Path, default=ROOT)
    sign = commands.add_parser("sign")
    sign.add_argument("--input-dir", type=Path, required=True)
    sign.add_argument("--output-dir", type=Path, required=True)
    sign.add_argument("--keystore", type=Path, required=True)
    sign.add_argument("--source-root", type=Path, default=ROOT)
    release = commands.add_parser("publish")
    release.add_argument("--artifacts", type=Path, required=True)
    release.add_argument("--head", required=True)
    release.add_argument("--version", required=True)
    release.add_argument("--prerelease", action="store_true")
    release.add_argument("--source-root", type=Path, default=ROOT)
    args = parser.parse_args()
    if args.command == "validate":
        validate(args.version, args.head, args.source_root)
    elif args.command == "graph":
        print(f"Verified {validate_graph(args.file.read_text())} host-only release tasks")
    elif args.command == "build":
        build(args.version, args.head, args.source_root)
    elif args.command == "sign":
        sign_and_verify(args.input_dir, args.output_dir, args.keystore, args.source_root)
        print("All release APKs signed and verified")
    elif args.command == "publish":
        publish(args.artifacts, args.head, args.version, args.prerelease, args.source_root)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.CalledProcessError) as error:
        # Never expose raw signing tool output, certificate subjects or secrets.
        print(f"Release operation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
