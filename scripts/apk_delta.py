#!/usr/bin/env python3
"""Produce ACCDLT01 binary deltas from exact signed APK bytes, no dependencies."""
import argparse
import hashlib
import os
from pathlib import Path
import struct

MAGIC = b'ACCDLT01'
BLOCK = 1024
MAX = 200 * 1024 * 1024


def generate(base: bytes, target: bytes, out):
    if not (0 < len(base) <= MAX and 0 < len(target) <= MAX):
        raise ValueError('APK size out of range')
    # Index aligned base blocks by their first 16 bytes. Full byte comparison
    # resolves collisions; limiting repeated candidates bounds pathological input.
    anchors = {}
    for pos in range(0, len(base) - BLOCK + 1, BLOCK):
        positions = anchors.setdefault(base[pos:pos + 16], [])
        if len(positions) < 8:
            positions.append(pos)
    out.write(MAGIC + hashlib.sha256(base).digest() + struct.pack('>Q', len(target)))
    pos = literal = 0

    def flush(end):
        if end > literal:
            out.write(struct.pack('>BI', 2, end - literal))
            out.write(target[literal:end])

    while pos + BLOCK <= len(target):
        candidates = anchors.get(target[pos:pos + 16], ())
        match = next((p for p in candidates if base[p:p + BLOCK] == target[pos:pos + BLOCK]), None)
        if match is None:
            pos += 1
            continue
        flush(pos)
        length = BLOCK
        limit = min(len(base) - match, len(target) - pos)
        # Extend contiguous matches in large chunks, then smaller ones.
        for step in (65536, BLOCK, 1):
            while length + step <= limit and base[match + length:match + length + step] == target[pos + length:pos + length + step]:
                length += step
        out.write(struct.pack('>BQI', 1, match, length))
        pos += length
        literal = pos
    flush(len(target))
    out.write(b'\0')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('base', type=Path)
    p.add_argument('target', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    for path in (args.base, args.target):
        if not 0 < path.stat().st_size <= MAX:
            p.error('APK size out of range')
    if args.output.resolve() in (args.base.resolve(), args.target.resolve()):
        p.error('output must differ from both inputs')
    tmp = args.output.with_name(args.output.name + '.tmp')
    try:
        with tmp.open('wb') as out:
            generate(args.base.read_bytes(), args.target.read_bytes(), out)
        os.replace(tmp, args.output)
    finally:
        tmp.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
