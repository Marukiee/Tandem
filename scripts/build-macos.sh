#!/usr/bin/env bash
# Builds Tandem.app. Usage: scripts/build-macos.sh [debug|release]
# Set VERSION and BUILD to stamp a release; UPDATE_PUBLIC_KEY to embed the update key.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="${1:-debug}"
VERSION="${VERSION:-0.1.0}"
BUILD="${BUILD:-1}"
UPDATE_KEY="${UPDATE_PUBLIC_KEY:-}"
DIST="macos/dist"
APP="$DIST/Tandem.app"

PROFILE=$([ "$CONFIG" = release ] && echo release || echo debug) ./scripts/build-core-macos.sh
( cd macos && swift build -c "$CONFIG" --arch arm64 2>&1 | grep -v "search path\|was built for newer" ; true )
BIN="$(cd macos && swift build -c "$CONFIG" --arch arm64 --show-bin-path 2>/dev/null)/Tandem"
[ -x "$BIN" ] || { echo "build failed: $BIN missing"; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Tandem"
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s|@UPDATE_KEY@|$UPDATE_KEY|" \
  macos/Resources/Info.plist >"$APP/Contents/Info.plist"

# Icon
ICONSET="$(mktemp -d)/AppIcon.iconset"
mkdir -p "$ICONSET"
swift scripts/make-icon.swift "$ICONSET/base.png" 1024
for size in 16 32 128 256 512; do
  sips -z $size $size "$ICONSET/base.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  sips -z $((size * 2)) $((size * 2)) "$ICONSET/base.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
rm "$ICONSET/base.png"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

# Translations
for lproj in macos/Resources/*.lproj; do
  [ -d "$lproj" ] && cp -R "$lproj" "$APP/Contents/Resources/"
done

./scripts/mac-signing.sh sign "$APP"
echo "built $APP ($CONFIG, $VERSION)"
