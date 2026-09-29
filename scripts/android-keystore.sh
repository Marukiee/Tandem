#!/usr/bin/env bash
# Creates the Android release keystore once and prints its SHA-256 fingerprint.
#
# Every APK must be signed with this same key, otherwise Android refuses to install
# an update over the previous version. So the key is made once, kept in ~/keystores
# and never regenerated: running this again only prints the fingerprint.
#
#   scripts/android-keystore.sh
set -euo pipefail

DIR="${TANDEM_KEYSTORES:-$HOME/keystores}"
JKS="$DIR/tandem-release.jks"
PASSFILE="$DIR/tandem-release.password"
ALIAS="tandem"

# /usr/bin/keytool on macOS is a stub that fails without a JDK on the path.
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
KEYTOOL="$JAVA_HOME/bin/keytool"
[ -x "$KEYTOOL" ] || KEYTOOL="$(command -v keytool)"

mkdir -p "$DIR"
chmod 700 "$DIR"

if [ ! -f "$JKS" ]; then
  echo "Creating the Android release key. Back up $DIR: losing it means everyone has to reinstall once." >&2
  umask 077
  # The password goes through a file so it never shows up in the process list. A
  # PKCS12 keystore uses one password for both the store and the key.
  openssl rand -hex 16 | tr -d '\n' >"$PASSFILE"
  "$KEYTOOL" -genkeypair -keystore "$JKS" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
    -dname "CN=Tandem, O=Mark Maakt Media, C=NL" \
    -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" >/dev/null
fi
[ -f "$PASSFILE" ] || { echo "$JKS exists but $PASSFILE is missing" >&2; exit 1; }

"$KEYTOOL" -exportcert -alias "$ALIAS" -keystore "$JKS" -storepass:file "$PASSFILE" 2>/dev/null \
  | openssl x509 -inform der -noout -fingerprint -sha256 | cut -d= -f2
