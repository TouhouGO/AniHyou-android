"""Validate, sign, and independently verify FOSS release APKs."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

PACKAGE = "com.axiel7.anihyou"
CERT_SHA = "c868d6a46cb0468d1fa87a9a52380db3c534dfee00e7cb356ac1264d3e7ea57e"
ABIS = {"universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
BUILD_RECEIPT = "anihyou-build-receipt.json"


def _version(root: Path) -> tuple[str, int]:
    props = {}
    for line in (root / "version.properties").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props["name"], int(props["code"])


def _names(version: str, unsigned: bool) -> set[str]:
    suffix = "-unsigned" if unsigned else ""
    return {f"anihyou-{version}-foss-{abi}-release{suffix}.apk" for abi in ABIS}


def _safe_name(name: object) -> bool:
    return isinstance(name, str) and bool(name) and name not in {".", ".."} and "/" not in name and "\\" not in name and Path(name).name == name


def _check_zip(path: Path, root: Path) -> None:
    resources = root / "core/localization/src/main/resources"
    expected = {p.relative_to(resources).as_posix(): p for p in resources.rglob("*") if p.is_file()}
    if not expected:
        raise ValueError("Localization resources are missing")
    try:
        with zipfile.ZipFile(path) as apk:
            infos = apk.infolist()
            names = [i.filename for i in infos]
            if len(names) != len(set(names)):
                raise ValueError(f"Duplicate ZIP entries: {path.name}")
            for name, source in expected.items():
                if name not in names or apk.read(name) != source.read_bytes():
                    raise ValueError(f"Missing or mismatched localization resource in {path.name}: {name}")
            for name in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"):
                if name not in names or not apk.read(name):
                    raise ValueError(f"Missing or empty baseline profile in {path.name}: {name}")
    except (zipfile.BadZipFile, OSError, KeyError) as exc:
        raise ValueError(f"Invalid APK ZIP: {path.name}") from exc


def validate_unsigned(input_dir: Path, root: Path) -> list[Path]:
    input_dir, root = Path(input_dir), Path(root)
    version, code = _version(root)
    try:
        metadata = json.loads((input_dir / "output-metadata.json").read_text())
        elements = metadata["elements"]
        if metadata["applicationId"] != PACKAGE or metadata["variantName"] != "fossRelease" or not isinstance(elements, list) or len(elements) != 5:
            raise ValueError("Invalid APK metadata identity or count")
        names, abis = set(), set()
        for element in elements:
            name = element["outputFile"]
            if not _safe_name(name) or name in names:
                raise ValueError("Unsafe or duplicate APK filename in metadata")
            filters = element["filters"]
            if element["type"] == "UNIVERSAL" and filters == []:
                abi = "universal"
            elif element["type"] == "ONE_OF_MANY" and len(filters) == 1 and filters[0]["filterType"] == "ABI":
                abi = filters[0]["value"]
            else:
                raise ValueError("Invalid ABI filter")
            if abi not in ABIS or abi in abis or name != f"anihyou-{version}-foss-{abi}-release-unsigned.apk" or element["versionCode"] != code or element["versionName"] != version:
                raise ValueError("APK metadata version, ABI, or filename mismatch")
            names.add(name)
            abis.add(abi)
        if abis != ABIS or names != _names(version, True):
            raise ValueError("Incomplete APK set")
        paths = [input_dir / name for name in sorted(names)]
        for path in paths:
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"Missing or unsafe APK: {path.name}")
            _check_zip(path, root)
        return paths
    except (KeyError, TypeError, json.JSONDecodeError, OSError) as exc:
        raise ValueError("Invalid or missing APK metadata") from exc


def _tool(name: str) -> Path:
    home = os.environ.get("ANDROID_HOME")
    if not home:
        raise ValueError("ANDROID_HOME is required")
    tools = Path(home) / "build-tools"
    versions = [p for p in tools.iterdir() if p.is_dir() and re.fullmatch(r"\d+(?:\.\d+)*", p.name)] if tools.is_dir() else []
    if not versions:
        raise ValueError("No Android build-tools found")
    executable = max(versions, key=lambda p: tuple(map(int, p.name.split(".")))) / name
    if not executable.is_file():
        raise ValueError(f"Missing Android build tool: {name}")
    return executable


def _run(args: list[str]) -> str:
    try:
        result = subprocess.run(args, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RuntimeError(f"Android tool failed: {Path(args[0]).name}") from exc
    return result.stdout


def _inspect(path: Path, root: Path) -> None:
    version, code = _version(root)
    _check_zip(path, root)
    _run([str(_tool("zipalign")), "-c", "4", str(path)])
    output = _run([str(_tool("apksigner")), "verify", "--verbose", "--print-certs", str(path)])
    digests = re.findall(
        r"(?im)^\s*(?:V\d+(?:\.\d+)?\s+)?Signer(?:\s+#?\d+)?\s*:?\s+certificate\s+SHA-256\s+digest:\s*([0-9a-f:]+)\s*$",
        output,
    )
    signer_count = re.search(r"(?im)^Number of signers:\s*(\d+)\s*$", output)
    if not signer_count or signer_count[1] != "1" or not digests or any(d.replace(":", "").lower() != CERT_SHA for d in digests):
        raise ValueError(f"APK signing certificate mismatch: {path.name}")
    badging = _run([str(_tool("aapt2")), "dump", "badging", str(path)])
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", package_line))
    if fields != {"name": PACKAGE, "versionCode": str(code), "versionName": version} or re.search(r"(?m)^application-debuggable(?:\s|$)", badging):
        raise ValueError(f"APK identity, version, or debuggable mismatch: {path.name}")


def _entry(path: Path) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"name": path.name, "bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def _source_commit(root: Path) -> str:
    commit = _run(["git", "-C", str(root), "rev-parse", "HEAD"]).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid source commit")
    return commit


def _source_tree(root: Path) -> str:
    return _run(["git", "-C", str(root), "rev-parse", "HEAD^{tree}"]).strip()


def _require_clean(root: Path) -> None:
    if _run(["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"]).strip():
        raise ValueError("Release source checkout must be clean")


def _build_record(input_dir: Path, root: Path) -> dict:
    paths = validate_unsigned(input_dir, root)
    version, code = _version(root)
    return {"formatVersion": 1, "sourceCommit": _source_commit(root),
            "sourceTree": _source_tree(root), "version": version, "versionCode": code,
            "target": ":app:assembleFossRelease", "artifacts": [_entry(path) for path in paths],
            "metadataSha256": hashlib.sha256((input_dir / "output-metadata.json").read_bytes()).hexdigest()}


def record_build(input_dir: Path, root: Path) -> dict:
    """Called only after the controlled build and post-build source-state check."""
    _require_clean(root)
    receipt = _build_record(input_dir, root)
    (input_dir / BUILD_RECEIPT).write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def _read_build(input_dir: Path, root: Path) -> dict:
    _require_clean(root)
    try:
        receipt = json.loads((input_dir / BUILD_RECEIPT).read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("Missing build receipt; run the controlled release build first") from exc
    if receipt != _build_record(input_dir, root):
        raise ValueError("APK build receipt does not match current source or unsigned artifacts")
    return receipt


def _check_record(receipt: dict, root: Path) -> None:
    version, code = _version(root)
    if not isinstance(receipt, dict) or receipt.get("formatVersion") != 1 or \
            receipt.get("sourceCommit") != _source_commit(root) or receipt.get("sourceTree") != _source_tree(root) or \
            receipt.get("version") != version or receipt.get("versionCode") != code or \
            receipt.get("target") != ":app:assembleFossRelease":
        raise ValueError("Release build record does not match the source checkout")
    entries = receipt.get("artifacts", [])
    if len(entries) != 5 or {entry.get("name") for entry in entries} != _names(version, True):
        raise ValueError("Build record has an incomplete unsigned artifact set")
    if not re.fullmatch(r"[0-9a-f]{64}", receipt.get("metadataSha256", "")) or any(
        type(entry.get("bytes")) is not int or entry["bytes"] <= 0 or
        not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")) for entry in entries
    ):
        raise ValueError("Build record has invalid artifact hashes")


def _sums(paths: list[Path], manifest_path: Path) -> str:
    entries = [_entry(path) for path in paths] + [_entry(manifest_path)]
    return "".join(f"{x['sha256']}  {x['name']}\n" for x in sorted(entries, key=lambda x: x['name']))


def _manifest(paths: list[Path], root: Path, build: dict) -> dict:
    version, code = _version(root)
    return {"formatVersion": 1, "version": version, "versionCode": code, "packageName": PACKAGE, "certSha": CERT_SHA, "sourceCommit": _source_commit(root), "build": build, "artifacts": [_entry(p) for p in sorted(paths)]}


def sign_and_verify(input_dir: Path, output_dir: Path, keystore: Path, root: Path) -> dict:
    input_dir, output_dir, root = Path(input_dir), Path(output_dir), Path(root)
    build = _read_build(input_dir, root)
    paths = validate_unsigned(input_dir, root)
    for key in ("KEY_ALIAS", "KEYSTORE_PASSWORD", "KEY_PASSWORD"):
        if not os.environ.get(key):
            raise ValueError(f"Missing required environment variable: {key}")
    if output_dir.exists() and (not output_dir.is_dir() or any(output_dir.iterdir())):
        raise ValueError("Output directory must be empty")
    output_dir.mkdir(parents=True, exist_ok=True)
    signed = []
    for path in paths:
        target = output_dir / path.name.replace("-unsigned.apk", ".apk")
        _run([str(_tool("apksigner")), "sign", "--ks", str(keystore), "--ks-key-alias", os.environ["KEY_ALIAS"], "--ks-pass", "env:KEYSTORE_PASSWORD", "--key-pass", "env:KEY_PASSWORD", "--v4-signing-enabled", "false", "--out", str(target), str(path)])
        _inspect(target, root)
        signed.append(target)
    if _read_build(input_dir, root) != build:
        raise ValueError("Release input changed while signing")
    manifest = _manifest(signed, root, build)
    (output_dir / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output_dir / "SHA256SUMS").write_text(_sums(signed, output_dir / "release-manifest.json"))
    return verify_artifacts(output_dir, root)


def verify_artifacts(output_dir: Path, root: Path) -> dict:
    output_dir, root = Path(output_dir), Path(root)
    version, _ = _version(root)
    expected = _names(version, False)
    files = {p.name for p in output_dir.iterdir()}
    if files != expected | {"release-manifest.json", "SHA256SUMS"}:
        raise ValueError("Release output has missing or unexpected files")
    paths = [output_dir / name for name in sorted(expected)]
    if any(p.is_symlink() or not p.is_file() for p in paths) or any((output_dir / n).is_symlink() for n in ("release-manifest.json", "SHA256SUMS")):
        raise ValueError("Release output contains unsafe files")
    for path in paths:
        _inspect(path, root)
    try:
        recorded = json.loads((output_dir / "release-manifest.json").read_text())
        sums = (output_dir / "SHA256SUMS").read_text()
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("Invalid release manifest") from exc
    _require_clean(root)
    _check_record(recorded.get("build"), root)
    manifest = _manifest(paths, root, recorded["build"])
    if recorded != manifest or sums != _sums(paths, output_dir / "release-manifest.json"):
        raise ValueError("Release manifest or checksums mismatch")
    return manifest
