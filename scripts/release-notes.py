#!/usr/bin/env python3
"""Prints the release notes for one version from changelog.json, as Markdown, in English.

Usage: scripts/release-notes.py 0.1.17
What is new comes first, the way the apps show it under What's new, and the table of files after a line. The update banners of
the apps show the part above that line only, so the table never ends up in them.
Without an entry for the version it prints a plain line, so the release never fails over it.
"""
import json
import sys
from pathlib import Path

version = sys.argv[1].lstrip("v") if len(sys.argv) > 1 else ""
entries = json.loads((Path(__file__).resolve().parent.parent / "changelog.json").read_text())
entry = next((e for e in entries if e["version"] == version), None)


def which_file(version: str) -> str:
    """What to download for your device. Only the files with the version in their name are listed. The ones without a number are
    the same files under a fixed name, which the apps and the links use to update themselves, so they can be ignored."""
    v = f"v{version}"
    return f"""## Which file do I need?

| You have | Download |
| --- | --- |
| Android phone | `Tandem-{v}.apk` |
| Mac (Apple silicon) | `Tandem-{v}-macOS.zip` |
| Windows 10 or 11 | `Tandem-{v}-Windows-x64-setup.exe` |
| Fedora, openSUSE (Linux) | `Tandem-{v}-Linux-x64.rpm`, or the AppImage |
| Ubuntu, Debian, Mint (Linux) | `Tandem-{v}-Linux-x64.deb`, or the AppImage |
| Any other Linux | `Tandem-{v}-Linux-x64.AppImage` (make it executable, then run it) |
| A server or Raspberry Pi without a screen | `tandemd-{v}-linux-x86_64.tar.gz` or `tandemd-{v}-linux-aarch64.tar.gz` |

Files without a version number in their name are copies the apps use to update themselves. You do not need them.
"""


if entry is None:
    print(f"Tandem {version}\n\n---\n")
    print(which_file(version))
    sys.exit(0)

text = entry["en"]
print(f"## {text['title']}\n")
for key, label in (("new", "New"), ("better", "Better"), ("fixed", "Fixed")):
    items = text.get(key)
    if items:
        print(f"**{label}**\n")
        print("\n".join(f"- {item}" for item in items))
        print()
print("---\n")
print(which_file(version))
