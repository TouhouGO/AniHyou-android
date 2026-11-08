#!/usr/bin/env python3
"""Seed the verified Kotlin compiler artifact used by the pinned Gradle wrapper."""
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

from prepare_gradle import check_wrapper
from network_retry import describe, run

VERSION = "2.4.10"
NAME = f"kotlin-compiler-embeddable-{VERSION}.jar"
URL = f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/{VERSION}/{NAME}"
SHA1 = "cac44a5a360313427c693a9de295017bc76d6e4e"
SHA256 = "9309638a2ee03e6bde9ef4b7444055a94b84ab906563675d71ff9aecb64da913"
SIZE = 60352373


def destination(home):
    return (Path(home) / "caches/modules-2/files-2.1/org.jetbrains.kotlin" /
            "kotlin-compiler-embeddable" / VERSION / SHA1 / NAME)


def matches(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    sha1, sha256 = hashlib.sha1(), hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            sha1.update(block)
            sha256.update(block)
    return sha1.hexdigest() == SHA1 and sha256.hexdigest() == SHA256


def download(candidate):
    candidate.unlink(missing_ok=True)
    sha1, sha256 = hashlib.sha1(), hashlib.sha256()
    total = 0
    request = urllib.request.Request(URL, headers={"User-Agent": "anihyou-ci-bootstrap"})
    with urllib.request.urlopen(request, timeout=60) as response, candidate.open("wb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > SIZE:
                raise ValueError("Kotlin compiler exceeds expected size")
            sha1.update(block)
            sha256.update(block)
            output.write(block)
    if total != SIZE or sha1.hexdigest() != SHA1 or sha256.hexdigest() != SHA256:
        raise ValueError("Kotlin compiler size or digest mismatch")

def prepare(home):
    check_wrapper(Path(__file__).resolve().parents[2] / "gradle/wrapper/gradle-wrapper.properties")
    target = destination(home)
    if matches(target):
        print("Verified Kotlin compiler artifact already cached")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent) as temp:
        candidate = Path(temp) / NAME
        run(lambda: download(candidate), "Kotlin compiler")
        os.replace(candidate, target)
    print("Verified Kotlin compiler artifact placed in Gradle cache")


if __name__ == "__main__":
    try:
        prepare(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    except (OSError, ValueError) as error:
        raise SystemExit(f"Unable to prepare Kotlin compiler artifact: {describe(error)}")
