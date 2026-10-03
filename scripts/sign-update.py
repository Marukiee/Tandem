#!/usr/bin/env python3
"""Signs a release file with the Ed25519 update key and prints the base64 signature.

The same key and the same signature as scripts/sign-update.swift, for the places that have Python and no Swift (the
Windows build). The apps check it over the raw bytes of the file: CryptoKit on the Mac, the core on Windows.

The key comes from TANDEM_UPDATE_ED25519_SECRET (the secret itself, in CI) or from the file TANDEM_UPDATE_KEY_FILE
(default ~/keystores/tandem-update-ed25519.secret).

Usage: python3 scripts/sign-update.py <file> > <file>.sig
"""
import base64
import os
import pathlib
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(1)


if len(sys.argv) != 2:
    fail("usage: sign-update.py <file>")
target = pathlib.Path(sys.argv[1])

secret = os.environ.get("TANDEM_UPDATE_ED25519_SECRET", "").strip()
if not secret:
    key_file = pathlib.Path(os.environ.get("TANDEM_UPDATE_KEY_FILE", "~/keystores/tandem-update-ed25519.secret")).expanduser()
    if not key_file.exists():
        fail(f"cannot read {key_file}")
    secret = key_file.read_text().strip()

try:
    key = Ed25519PrivateKey.from_private_bytes(base64.b64decode(secret, validate=True))
except Exception:
    fail("the update key is not a base64 Ed25519 private key")

# Refuse to sign with a key the shipped apps would not accept: catching it here is cheaper than a release that no
# installed app can update to.
shipped = pathlib.Path(__file__).resolve().parent.parent / "macos" / "update-public-key.txt"
if shipped.exists():
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    if base64.b64encode(public).decode() != shipped.read_text().strip():
        fail("this key does not match macos/update-public-key.txt, installed apps would reject the update")

print(base64.b64encode(key.sign(target.read_bytes())).decode())
