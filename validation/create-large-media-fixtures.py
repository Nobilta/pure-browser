#!/usr/bin/env python3
"""Create valid sparse MP4s with media samples at large offsets; no large download required."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent

def create(name, total):
    source = bytearray((ROOT / 'player-sample.mp4').read_bytes())
    gap = total - len(source)
    assert 8 <= gap <= 0xffffffff
    containers = {b'moov', b'trak', b'mdia', b'minf', b'stbl'}
    def patch(start, end):
        pos = start
        while pos < end:
            length, kind = struct.unpack_from('>I4s', source, pos)
            assert length >= 8 and pos + length <= end
            if kind in containers:
                patch(pos + 8, pos + length)
            elif kind in (b'stco', b'co64'):
                count = struct.unpack_from('>I', source, pos + 12)[0]
                fmt, width = ('>I', 4) if kind == b'stco' else ('>Q', 8)
                for i in range(count):
                    at = pos + 16 + i * width
                    struct.pack_into(fmt, source, at, struct.unpack_from(fmt, source, at)[0] + gap)
            pos += length
    patch(0, len(source))
    first = struct.unpack_from('>I', source, 0)[0]
    split = first + struct.unpack_from('>I', source, first)[0]
    path = ROOT / 'results' / (name + '.mp4')
    path.parent.mkdir(exist_ok=True)
    with path.open('wb') as out:
        out.write(source[:split]); out.write(struct.pack('>I4s', gap, b'free'))
        out.seek(gap - 8, 1); out.write(source[split:])
    assert path.stat().st_size == total
    print(path, total)

if __name__ == '__main__':
    create('large', 1024 ** 3)
    create('boundary', 2 ** 31 - 1)
    create('over-limit', 2 ** 31 + 1024 ** 2)
