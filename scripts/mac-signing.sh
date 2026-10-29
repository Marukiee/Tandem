#!/usr/bin/env bash
# Permanent code signing for the Mac app, without an Apple Developer account.
#
# Updates only keep their permissions (Bluetooth, local network, notifications,
# accessibility) and Keychain items when every build is signed by the same
# certificate. So the certificate is made once, kept in ~/keystores, and used for
# every release. It lives in its own keychain file so the login keychain is never
# touched.
#
#   mac-signing.sh setup            create the certificate (once) and its keychain
#   mac-signing.sh sign <app>       sign an app bundle
#   mac-signing.sh export           print base64 of the p12 for GitHub secrets
#   mac-signing.sh ci               in CI: build the keychain from MACOS_CERT_* secrets
#
# With TANDEM_SIGN=developer-id the Developer ID Application certificate of team X8Y2M32MNQ signs instead (in CI from the
# DEVELOPER_ID_* secrets), with Apple's timestamp, so the app can be notarized. The updater takes both (Updater.swift).
set -euo pipefail

DIR="${TANDEM_KEYSTORES:-$HOME/keystores}"
KEYCHAIN="$DIR/tandem-signing.keychain-db"
if [ "${TANDEM_SIGN:-}" = developer-id ]; then
  P12="$DIR/tandem-developer-id.p12"
  PASSFILE="$DIR/tandem-developer-id.password"
  IDENTITY="Developer ID Application: Mark Mossing Holsteijn (X8Y2M32MNQ)"
else
  P12="$DIR/tandem-mac-signing.p12"
  PASSFILE="$DIR/tandem-mac-signing.password"
  IDENTITY="Tandem Signing"
fi
ENTITLEMENTS="$(cd "$(dirname "$0")/.." && pwd)/macos/Resources/Tandem.entitlements"

password() { cat "$PASSFILE"; }

make_keychain() {
  local pass; pass="$(password)"
  rm -f "$KEYCHAIN"
  security create-keychain -p "$pass" "$KEYCHAIN"
  security set-keychain-settings "$KEYCHAIN"          # never lock on a timer
  security unlock-keychain -p "$pass" "$KEYCHAIN"
  security import "$P12" -k "$KEYCHAIN" -P "$pass" -T /usr/bin/codesign -T /usr/bin/security >/dev/null
  # Lets codesign use the key without a prompt.
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$pass" "$KEYCHAIN" >/dev/null
}

cmd_setup() {
  mkdir -p "$DIR"; chmod 700 "$DIR"
  if [ ! -f "$P12" ]; then
    echo "Creating a new signing certificate. Back up $DIR: losing it means everyone has to reinstall once."
    umask 077
    openssl rand -hex 16 | tr -d '\n' >"$PASSFILE"
    local tmp; tmp="$(mktemp -d)"
    cat >"$tmp/openssl.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $IDENTITY
O = Mark Maakt Media
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
    openssl req -x509 -newkey rsa:2048 -nodes -days 36500 -config "$tmp/openssl.cnf" \
      -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
    openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" -name "$IDENTITY" \
      -out "$P12" -passout "pass:$(password)"
    rm -rf "$tmp"
  fi
  make_keychain
  echo "Signing identity ready:"
  security find-identity -p codesigning "$KEYCHAIN" | sed 's/^/  /'
  echo "SHA-256 of the certificate: $(openssl pkcs12 -in "$P12" -clcerts -nokeys -passin "pass:$(password)" 2>/dev/null | openssl x509 -outform der | shasum -a 256 | cut -d' ' -f1)"
}

cmd_ci_developer_id() {
  : "${DEVELOPER_ID_P12_B64:?missing}"; : "${DEVELOPER_ID_PASSWORD:?missing}"
  mkdir -p "$DIR"
  printf '%s' "$DEVELOPER_ID_P12_B64" | base64 --decode >"$P12"
  printf '%s' "$DEVELOPER_ID_PASSWORD" >"$PASSFILE"
  make_keychain
  # The intermediate of Apple that the certificate hangs from; a runner usually has it, this makes sure.
  curl -fsSL https://www.apple.com/certificateauthority/DeveloperIDG2CA.cer -o "$DIR/DeveloperIDG2CA.cer" \
    && security import "$DIR/DeveloperIDG2CA.cer" -k "$KEYCHAIN" >/dev/null 2>&1 || true
  security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | tr -d '"') || true
  security find-identity -v -p codesigning "$KEYCHAIN" || true
}

cmd_ci() {
  if [ "${TANDEM_SIGN:-}" = developer-id ]; then cmd_ci_developer_id; return; fi
  : "${MACOS_CERT_P12_B64:?missing}"; : "${MACOS_CERT_PASSWORD:?missing}"
  mkdir -p "$DIR"
  printf '%s' "$MACOS_CERT_P12_B64" | base64 --decode >"$P12"
  printf '%s' "$MACOS_CERT_PASSWORD" >"$PASSFILE"
  make_keychain
  # A self-made certificate is only offered to codesign once the machine trusts it for
  # code signing, and a fresh runner has never seen it. The signature itself does not change.
  local pem="$DIR/tandem-mac-signing.pem"
  security find-certificate -c "$IDENTITY" -p "$KEYCHAIN" >"$pem"
  sudo security add-trusted-cert -d -r trustRoot -p codeSign -k /Library/Keychains/System.keychain "$pem" \
    || security add-trusted-cert -r trustRoot -p codeSign -k "$KEYCHAIN" "$pem" || true
  # codesign only looks in the keychains on the search list.
  security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | tr -d '"') || true
  security find-identity -p codesigning || true
}

cmd_sign() {
  local app="${1:?usage: sign <app>}"
  local entitlements="${2:-$ENTITLEMENTS}"
  if [ ! -f "$KEYCHAIN" ]; then
    # A Developer ID comes from Apple and cannot be made here.
    [ "${TANDEM_SIGN:-}" = developer-id ] && { echo "no keychain with the Developer ID: run 'ci' first"; exit 1; }
    cmd_setup
  fi
  security unlock-keychain -p "$(password)" "$KEYCHAIN"
  # Notarization wants Apple's timestamp; the self-made certificate has no use for one.
  local stamp=--timestamp=none
  [ "${TANDEM_SIGN:-}" = developer-id ] && stamp=--timestamp
  codesign --force --options runtime "$stamp" \
    --entitlements "$entitlements" --keychain "$KEYCHAIN" --sign "$IDENTITY" "$app"
  codesign --verify --strict "$app"
  echo "signed $app"
}

cmd_export() { base64 <"$P12" | tr -d '\n'; echo; }

case "${1:-}" in
  setup) cmd_setup ;;
  ci) cmd_ci ;;
  sign) shift; cmd_sign "$@" ;;
  export) cmd_export ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
