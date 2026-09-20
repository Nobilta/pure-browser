#!/usr/bin/env python3
"""Verify/rebuild the source lexer's pinned BMP character tables using OpenJDK 26.0.2.

Use --check for a non-mutating full-BMP comparison, --write to regenerate. JAVA may
select a java executable. Changing the Unicode baseline requires deliberately updating
BASELINE and reviewing the resulting diff, rather than following the host JDK silently.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

BASELINE = "26.0.2"
SOURCE = Path(__file__).resolve().parents[1] / "url_utils/src/source.rs"
JAVA_SOURCE = """
class CharacterTables {
    public static void main(String[] args) {
        System.out.println(System.getProperty("java.version"));
        for (int i = 0; i <= 0xffff; i++) {
            char c = (char)i;
            System.out.println((Character.isDigit(c) ? 1 : 0) + "," +
                (Character.isLetter(c) ? 1 : 0) + "," +
                ((Character.isWhitespace(c) || Character.isSpaceChar(c)) ? 1 : 0));
        }
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="pure-character-tables-") as directory:
        program = Path(directory) / "CharacterTables.java"
        program.write_text(JAVA_SOURCE, encoding="utf-8")
        rows = subprocess.check_output([os.environ.get("JAVA", "java"), str(program)], text=True).splitlines()
    version = rows.pop(0)
    if not re.fullmatch(re.escape(BASELINE) + r"(?:[.+_-].*)?", version):
        raise SystemExit(f"Expected pinned JDK {BASELINE} (including build/patch suffixes), got {version}")
    if len(rows) != 65536:
        raise SystemExit("Incomplete BMP output")
    classifications = [[int(value) for value in row.split(",")] for row in rows]
    source = SOURCE.read_text(encoding="utf-8")
    mismatches = []
    for column, name in enumerate(("DIGIT", "LETTER", "WHITESPACE")):
        points = [i for i, row in enumerate(classifications) if row[column]]
        ranges = []
        for point in points:
            if ranges and point == ranges[-1][1] + 1:
                ranges[-1] = (ranges[-1][0], point)
            else:
                ranges.append((point, point))
        pattern = rf"static JAVA_{name}_RANGES: \[\(u16, u16\); \d+\] = \[(.*?)\];"
        match = re.search(pattern, source, re.S)
        if not match:
            raise SystemExit(f"Missing JAVA_{name}_RANGES")
        old = [(int(a, 16), int(b, 16)) for a, b in re.findall(r"\(0x([0-9A-F]+), 0x([0-9A-F]+)\)", match[1])]
        if old != ranges:
            mismatches.append(name)
        replacement = f"static JAVA_{name}_RANGES: [(u16, u16); {len(ranges)}] = [\n"
        replacement += "".join(f"    (0x{start:04X}, 0x{end:04X}),\n" for start, end in ranges) + "];"
        source = re.sub(pattern, lambda _: replacement, source, count=1, flags=re.S)
        print(f"{name}: {len(ranges)} ranges, {len(points)} BMP values")
    if args.write:
        SOURCE.write_text(source, encoding="utf-8")
    elif mismatches:
        raise SystemExit("Tables differ: " + ", ".join(mismatches))
    print(f"PASS: all 65,536 BMP values match JDK {version}" if not mismatches else "Tables regenerated; review the diff")


if __name__ == "__main__":
    main()
