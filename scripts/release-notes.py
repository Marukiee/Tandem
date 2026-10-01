#!/usr/bin/env python3
"""Prints the release notes for one version from changelog.json, as Markdown.

Usage: scripts/release-notes.py 0.1.17
The text is the same the apps show under What's new, so the release page and the apps agree.
Without an entry for the version it prints a plain line, so the release never fails over it.
"""
import json
import sys
from pathlib import Path

version = sys.argv[1].lstrip("v") if len(sys.argv) > 1 else ""
entries = json.loads((Path(__file__).resolve().parent.parent / "changelog.json").read_text())
entry = next((e for e in entries if e["version"] == version), None)
if entry is None:
    print(f"Tandem {version}")
    sys.exit(0)

groups = [("new", "New", "Nieuw"), ("better", "Better", "Beter"), ("fixed", "Fixed", "Opgelost")]
for lang, label in (("en", "English"), ("nl", "Nederlands")):
    text = entry[lang]
    print(f"## {text['title']} ({label})\n")
    for key, en, nl in groups:
        items = text.get(key)
        if items:
            print(f"**{en if lang == 'en' else nl}**\n")
            print("\n".join(f"- {item}" for item in items))
            print()
