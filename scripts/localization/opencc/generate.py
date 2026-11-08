#!/usr/bin/env python3
"""Generate the search-only first-target OpenCC map; --check verifies the artifact."""
import argparse
import hashlib
from pathlib import Path
import struct

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = HERE / 'TSCharacters.txt'
OUTPUT = ROOT / 'core/localization/src/main/resources/localization/opencc/ts-characters.bin'
SHA256 = '6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a'


def canonicalize(mapping):
    result = {}
    for key in mapping:
        path = set()
        point = key
        while point in mapping:
            if mapping[point] == point:
                break
            if point in path:
                raise ValueError(f'cycle at U+{point:04X}')
            path.add(point)
            point = mapping[point]
        result[key] = point
    return result


def generate(data):
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('unexpected source SHA-256')
    mapping = {}
    for line in data.decode('utf-8').splitlines():
        key, values = line.split('\t')
        targets = values.split()
        if len(key) != 1 or not targets or any(len(target) != 1 for target in targets):
            raise ValueError(f'expected single code points: {line}')
        if ord(key) in mapping:
            raise ValueError(f'duplicate key: {key}')
        mapping[ord(key)] = ord(targets[0])
    if len(mapping) != 4113:
        raise ValueError('unexpected mapping count')
    pairs = sorted(canonicalize(mapping).items())
    return struct.pack('>I', len(pairs)) + b''.join(struct.pack('>II', *pair) for pair in pairs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    output = generate(SOURCE.read_bytes())
    if args.check:
        if OUTPUT.read_bytes() != output:
            raise SystemExit('generated map differs')
    else:
        OUTPUT.write_bytes(output)


if __name__ == '__main__':
    main()
