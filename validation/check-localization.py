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
    for plural in root.findall("plurals"):
        name = plural.attrib["name"]
        forms = {item.attrib["quantity"]: item.text or "" for item in plural.findall("item")}
        assert "other" in forms, "Plural missing default: " + name
        expected = sorted(re.findall(r"%\d+\$[sdif]", forms["other"]))
        for value in forms.values():
            assert sorted(re.findall(r"%\d+\$[sdif]", value)) == expected, "Plural format differs: " + name
        values["plurals/" + name] = (forms["other"], "true")
    return values


english = strings("values")
chinese = {folder: strings(folder) for folder in ("values-zh", "values-b+zh+Hant")}
for folder, translated in chinese.items():
    assert english.keys() == translated.keys(), "Resource keys differ: " + folder
for name, (text, formatted) in english.items():
    assert not re.search(r"[\u3400-\u9fff]", text), "Chinese in English resource: " + name
    for folder, translated in chinese.items():
        other, other_formatted = translated[name]
        assert formatted == other_formatted, "Formatting mode differs: " + folder + "/" + name
        if formatted == "false":
            continue
        arguments = lambda value: sorted(re.findall(r"%\d+\$[sdif]", value))
        assert arguments(text) == arguments(other), "Format arguments differ: " + folder + "/" + name
        for value in (text, other):
            assert not re.search(r"%(?!%|\d+\$[sdif])", value.replace("%%", "")), "Unescaped percent: " + name

print(f"PASS: {len(english)} English, Simplified Chinese and Traditional Chinese resources with matching format arguments")
