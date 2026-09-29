#!/usr/bin/env bash
# Uploads the signing material in ~/keystores to the GitHub repository as Actions
# secrets, so the release workflow signs with the same keys as local builds.
#
# Values travel through pipes into `gh secret set`, never through arguments or
# output, so nothing shows up in the process list or in a terminal log. Safe to run
# again: it overwrites the secrets with the same values.
#
#   scripts/setup-secrets.sh
set -euo pipefail

REPO="${TANDEM_REPO:-Marukiee/Tandem}"
DIR="${TANDEM_KEYSTORES:-$HOME/keystores}"

for file in tandem-release.jks tandem-release.password tandem-mac-signing.p12 \
  tandem-mac-signing.password tandem-update-ed25519.secret; do
  [ -s "$DIR/$file" ] || { echo "missing $DIR/$file (run the key scripts first)" >&2; exit 1; }
done

set_secret() { # name, then the value on stdin
  gh secret set "$1" --repo "$REPO" >/dev/null
  echo "set $1"
}
b64() { base64 <"$1" | tr -d '\n'; }
text() { tr -d '[:space:]' <"$1"; }

b64 "$DIR/tandem-release.jks" | set_secret ANDROID_KEYSTORE_B64
text "$DIR/tandem-release.password" | set_secret ANDROID_KEYSTORE_PASSWORD
printf '%s' tandem | set_secret ANDROID_KEY_ALIAS
# A PKCS12 keystore has one password for the store and the key.
text "$DIR/tandem-release.password" | set_secret ANDROID_KEY_PASSWORD

b64 "$DIR/tandem-mac-signing.p12" | set_secret MACOS_CERT_P12_B64
text "$DIR/tandem-mac-signing.password" | set_secret MACOS_CERT_PASSWORD

text "$DIR/tandem-update-ed25519.secret" | set_secret TANDEM_UPDATE_ED25519_SECRET

echo "done, names only:"
gh secret list --repo "$REPO"
