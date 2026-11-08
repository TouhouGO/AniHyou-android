#!/usr/bin/env python3
"""Seed the Gradle wrapper cache with the verified official distribution."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.request

VERSION = "9.8.2"
NAME = f"gradle-{VERSION}-bin.zip"
URL = f"https://services.gradle.org/distributions/{NAME}"
RELEASE_API = f"https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v{VERSION}"
SHA256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
SIZE = 151620138
MAX_SIZE = 200 * 1024 * 1024


def check_wrapper(properties):
    entries = dict(line.split("=", 1) for line in Path(properties).read_text().splitlines()
                   if "=" in line and not line.lstrip().startswith("#"))
    if entries.get("distributionUrl", "").replace("\\:", ":") != URL:
        raise ValueError("Update the pinned Gradle bootstrap together with the wrapper")


def cache_dir(home):
    number = int.from_bytes(hashlib.md5(URL.encode()).digest(), "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    encoded = ""
    while number:
        number, digit = divmod(number, 36)
        encoded = alphabet[digit] + encoded
    return Path(home) / "wrapper" / "dists" / f"gradle-{VERSION}-bin" / encoded


def request(url, accept="application/octet-stream"):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        "Accept": accept, "User-Agent": "anihyou-gradle-bootstrap"}), timeout=60)


def asset_url():
    with request(RELEASE_API, "application/vnd.github+json") as response:
        data = response.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024:
        raise ValueError("Release metadata exceeds limit")
    release = json.loads(data)
    assets = [a for a in release.get("assets", []) if a.get("name") == NAME]
    if release.get("tag_name") != f"v{VERSION}" or len(assets) != 1:
        raise ValueError("Release version or asset name mismatch")
    asset = assets[0]
    asset_id = asset.get("id")
    if type(asset_id) is not int or asset_id <= 0 or asset.get("size") != SIZE:
        raise ValueError("Release asset metadata mismatch")
    return f"https://api.github.com/repos/gradle/gradle-distributions/releases/assets/{asset_id}"


def verified(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == SHA256


def download(url, destination):
    digest = hashlib.sha256()
    total = 0
    with request(url) as response, destination.open("wb") as target:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > MAX_SIZE or total > SIZE:
                raise ValueError("Distribution exceeds expected size")
            digest.update(block)
            target.write(block)
    if total != SIZE or digest.hexdigest() != SHA256:
        raise ValueError("Distribution size or SHA-256 mismatch")


def prepare(home):
    directory = cache_dir(home)
    archive = directory / NAME
    if (directory / (NAME + ".ok")).is_file() and (directory / f"gradle-{VERSION}" / "lib").is_dir():
        print("Gradle wrapper distribution already installed")
        return
    if verified(archive):
        print("Verified Gradle wrapper archive already cached")
        return
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=directory) as temp:
        target = Path(temp) / NAME
        try:
            print("Fetching official Gradle distribution")
            download(URL, target)
        except (OSError, ValueError):
            print("Official distribution download failed; trying official release asset")
            download(asset_url(), target)
        os.replace(target, archive)
    print("Verified Gradle distribution placed in wrapper cache")


if __name__ == "__main__":
    try:
        check_wrapper(Path(__file__).resolve().parents[2] / "gradle/wrapper/gradle-wrapper.properties")
        prepare(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        raise SystemExit(f"Unable to prepare Gradle distribution: {type(error).__name__}")
