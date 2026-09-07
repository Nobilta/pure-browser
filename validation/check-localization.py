#!/usr/bin/env python3
"""Keep English/Chinese app messages and their formatting arguments in sync."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def strings(folder):
    root = ET.parse(RES / folder / "strings.xml").getroot()
    values = {}
    for item in root.findall("string"):
        name = item.attrib["name"]
        assert name not in values, "Duplicate string: " + name
        values[name] = (item.text or "", item.attrib.get("formatted", "true"))
    return values


english, chinese = strings("values"), strings("values-zh")
assert english.keys() == chinese.keys(), "English/Chinese resource keys differ"
for name, (text, formatted) in english.items():
    assert not re.search(r"[\u3400-\u9fff]", text), "Chinese in English resource: " + name
    other, other_formatted = chinese[name]
    assert formatted == other_formatted, "Formatting mode differs: " + name
    if formatted == "false":
        continue
    arguments = lambda value: sorted(re.findall(r"%\d+\$[sdif]", value))
    assert arguments(text) == arguments(other), "Format arguments differ: " + name
    for value in (text, other):
        assert not re.search(r"%(?!%|\d+\$[sdif])", value.replace("%%", "")), "Unescaped percent: " + name

print(f"PASS: {len(english)} English/Chinese resources with matching format arguments")
