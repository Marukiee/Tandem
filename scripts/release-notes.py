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

def which_file(version: str) -> str:
    """A short table at the top of the release: what to download for your device. Only the files with the version in their name
    are listed. The ones without a number are the same files under a fixed name, which the apps and the links use to update
    themselves, so they can be ignored."""
    v = f"v{version}"
    return f"""## Which file do I need? / Welk bestand heb ik nodig?

| You have / Je hebt | Download |
| --- | --- |
| Android phone / Android-telefoon | `Tandem-{v}.apk` |
| Mac (Apple silicon) | `Tandem-{v}-macOS.zip` |
| Windows 10 or 11 | `Tandem-{v}-Windows-x64-setup.exe` |
| Fedora, openSUSE (Linux) | `Tandem-{v}-Linux-x64.rpm`, or the AppImage |
| Ubuntu, Debian, Mint (Linux) | `Tandem-{v}-Linux-x64.deb`, or the AppImage |
| Any other Linux / Elke andere Linux | `Tandem-{v}-Linux-x64.AppImage` (make it executable, then run it) |
| A server or Raspberry Pi without a screen / Zonder scherm | `tandemd-{v}-linux-x86_64.tar.gz` or `tandemd-{v}-linux-aarch64.tar.gz` |

Files without a version number in their name are copies the apps use to update themselves. You do not need them.
Bestanden zonder versienummer zijn kopieen die de apps gebruiken om zichzelf bij te werken. Die heb je niet nodig.

"""


groups = [("new", "New", "Nieuw"), ("better", "Better", "Beter"), ("fixed", "Fixed", "Opgelost")]
print(which_file(version))
for lang, label in (("en", "English"), ("nl", "Nederlands")):
    text = entry[lang]
    print(f"## {text['title']} ({label})\n")
    for key, en, nl in groups:
        items = text.get(key)
        if items:
            print(f"**{en if lang == 'en' else nl}**\n")
            print("\n".join(f"- {item}" for item in items))
            print()
