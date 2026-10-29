#!/usr/bin/env bash
# Builds Tandem.app. Usage: scripts/build-macos.sh [debug|release]
# Set VERSION and BUILD to stamp a release. The update key comes from
# macos/update-public-key.txt unless UPDATE_PUBLIC_KEY overrides it.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="${1:-debug}"
VERSION="${VERSION:-0.1.0}"
BUILD="${BUILD:-1}"
UPDATE_KEY="${UPDATE_PUBLIC_KEY:-}"
[ -n "$UPDATE_KEY" ] || UPDATE_KEY="$(tr -d '[:space:]' <macos/update-public-key.txt 2>/dev/null || true)"
# An app without the key installs updates without checking who signed them.
[ "$CONFIG" != release ] || [ -n "$UPDATE_KEY" ] || { echo "no update public key for a release build"; exit 1; }
DIST="macos/dist"
# A debug build is a separate app, so testing never touches the real identity, pairings
# or preferences: another bundle id, and its data lives in Application Support/Tandem-Dev.
if [ "$CONFIG" = release ]; then
  BUNDLE_ID="nl.markmaaktmedia.Tandem"; APP_NAME="Tandem"
else
  BUNDLE_ID="nl.markmaaktmedia.Tandem.dev"; APP_NAME="Tandem Dev"
fi
APP="$DIST/$APP_NAME.app"

PROFILE=$([ "$CONFIG" = release ] && echo release || echo debug) ./scripts/build-core-macos.sh
( cd macos && swift build -c "$CONFIG" --arch arm64 2>&1 | grep -v "search path\|was built for newer" ; true )
BIN="$(cd macos && swift build -c "$CONFIG" --arch arm64 --show-bin-path 2>/dev/null)/Tandem"
[ -x "$BIN" ] || { echo "build failed: $BIN missing"; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Tandem"
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s|@UPDATE_KEY@|$UPDATE_KEY|" \
  -e "s|@BUNDLE_ID@|$BUNDLE_ID|" -e "s|@APP_NAME@|$APP_NAME|" \
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

# What's new
cp changelog.json "$APP/Contents/Resources/changelog.json"

# Translations
for lproj in macos/Resources/*.lproj; do
  [ -d "$lproj" ] && cp -R "$lproj" "$APP/Contents/Resources/"
done

# The Share menu: an app extension, built without an Xcode project. It is signed first, with its own (sandbox) entitlements.
EXT="$APP/Contents/PlugIns/TandemShare.appex"
mkdir -p "$EXT/Contents/MacOS"
swiftc -O -parse-as-library -module-name TandemShare -application-extension -target arm64-apple-macos26.0 \
  -framework AppKit -Xlinker -e -Xlinker _NSExtensionMain \
  -o "$EXT/Contents/MacOS/TandemShare" macos/ShareExtension/ShareViewController.swift
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s|@BUNDLE_ID@|$BUNDLE_ID|" macos/ShareExtension/Info.plist >"$EXT/Contents/Info.plist"
./scripts/mac-signing.sh sign "$EXT" macos/Resources/TandemShare.entitlements

# The files of a phone in the Finder sidebar: a File Provider extension, built without an Xcode project like the Share menu. It
# finds the address of each phone's server in an app group that it shares with the app, and the app only gets that group with
# the Developer ID and its profile (the camera block below), so it is built in under the same condition. Without it the app keeps
# the mounted drive. The extension claims nothing that needs a profile; one of its own (App ID <bundle id>.Files) goes in when there is one.
FILES_APP_PROFILE="${TANDEM_PROFILE:-$HOME/keystores/$([ "$CONFIG" = release ] && echo tandem || echo tandem-dev).provisionprofile}"
FILES_PROFILE="${TANDEM_FILES_PROFILE:-$HOME/keystores/$([ "$CONFIG" = release ] && echo tandem-files || echo tandem-dev-files).provisionprofile}"
if [ "${TANDEM_SIGN:-}" = developer-id ] && [ -f "$FILES_APP_PROFILE" ]; then
  FPX="$APP/Contents/PlugIns/TandemFiles.appex"
  mkdir -p "$FPX/Contents/MacOS"
  swiftc -O -swift-version 5 -parse-as-library -module-name TandemFiles -application-extension -target arm64-apple-macos26.0 \
    -framework FileProvider -framework UniformTypeIdentifiers -Xlinker -e -Xlinker _NSExtensionMain \
    -o "$FPX/Contents/MacOS/TandemFiles" macos/FileProviderExtension/FileProviderExtension.swift macos/FileProviderExtension/WebDAVClient.swift
  sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s|@BUNDLE_ID@|$BUNDLE_ID|" macos/FileProviderExtension/Info.plist >"$FPX/Contents/Info.plist"
  FILES_WORK="$(mktemp -d)"
  sed -e "s|@BUNDLE_ID@|$BUNDLE_ID|" macos/FileProviderExtension/TandemFiles.entitlements >"$FILES_WORK/files.entitlements"
  if [ -f "$FILES_PROFILE" ]; then
    cp "$FILES_PROFILE" "$FPX/Contents/embedded.provisionprofile"
    /usr/libexec/PlistBuddy \
      -c "Add :com.apple.application-identifier string X8Y2M32MNQ.$BUNDLE_ID.Files" \
      -c "Add :com.apple.developer.team-identifier string X8Y2M32MNQ" \
      "$FILES_WORK/files.entitlements" >/dev/null
  fi
  ./scripts/mac-signing.sh sign "$FPX" "$FILES_WORK/files.entitlements"
fi

# The buttons of the Control Center: a WidgetKit extension, which needs the metadata of its intents that only xcodebuild makes. Signed first too.
CTL="$APP/Contents/PlugIns/TandemControls.appex"
SUPPORT_DIR=$([ "$CONFIG" = release ] && echo Tandem || echo Tandem-Dev)
CTL_BUILD="$(mktemp -d)"
xcodebuild -project macos/ControlExtension/TandemControls.xcodeproj -target TandemControls -configuration Release \
  CONFIGURATION_BUILD_DIR="$CTL_BUILD" SYMROOT="$CTL_BUILD/sym" OBJROOT="$CTL_BUILD/obj" PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID.Controls" \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" TANDEM_SUPPORT_DIR="$SUPPORT_DIR" build >"$CTL_BUILD/log.txt" 2>&1 \
  || { tail -30 "$CTL_BUILD/log.txt"; echo "the Control Center extension did not build"; exit 1; }
rm -rf "$CTL"
cp -R "$CTL_BUILD/TandemControls.appex" "$CTL"
./scripts/mac-signing.sh sign "$CTL" macos/Resources/TandemControls.entitlements

# Tandem's sound devices (Tandem Speaker and Tandem Microphone): a Core Audio driver in C, carried in the app. The app copies it to
# /Library/Audio/Plug-Ins/HAL when the person turns it on, with their password; its own version (Info.plist there) says when to again.
DRV="$APP/Contents/Resources/TandemAudio.driver"
mkdir -p "$DRV/Contents/MacOS"
clang -bundle -arch arm64 -mmacosx-version-min=26.0 -O2 -Wall -fvisibility=hidden -framework CoreAudio -framework CoreFoundation \
  -o "$DRV/Contents/MacOS/TandemAudio" macos/AudioDriver/TandemAudio.c macos/AudioDriver/ring.c
cp macos/AudioDriver/Info.plist "$DRV/Contents/Info.plist"
./scripts/mac-signing.sh sign "$DRV" macos/AudioDriver/TandemAudio.entitlements

# Tandem Camera, the phone as a webcam: a system extension, which macOS only lets an app install with a provisioning profile of the
# Developer ID that allows it. Without both (a build signed with the own certificate, or no profile yet) it is left out, and so is the
# entitlement: an app that claims it without the profile is stopped at launch.
APP_ENTITLEMENTS=macos/Resources/Tandem.entitlements
PROFILE_FILE="${TANDEM_PROFILE:-$HOME/keystores/$([ "$CONFIG" = release ] && echo tandem || echo tandem-dev).provisionprofile}"
if [ "${TANDEM_SIGN:-}" = developer-id ] && [ -f "$PROFILE_FILE" ]; then
  SYSX="$APP/Contents/Library/SystemExtensions/$BUNDLE_ID.Camera.systemextension"
  mkdir -p "$SYSX/Contents/MacOS"
  swiftc -O -swift-version 5 -module-name TandemCamera -target arm64-apple-macos26.0 \
    -framework CoreMediaIO -framework CoreMedia -framework CoreVideo \
    -o "$SYSX/Contents/MacOS/TandemCamera" macos/CameraExtension/main.swift
  sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s|@BUNDLE_ID@|$BUNDLE_ID|" macos/CameraExtension/Info.plist >"$SYSX/Contents/Info.plist"
  WORK="$(mktemp -d)"
  sed -e "s|@BUNDLE_ID@|$BUNDLE_ID|" macos/CameraExtension/TandemCamera.entitlements >"$WORK/camera.entitlements"
  ./scripts/mac-signing.sh sign "$SYSX" "$WORK/camera.entitlements"
  cp "$PROFILE_FILE" "$APP/Contents/embedded.provisionprofile"
  APP_ENTITLEMENTS="$WORK/app.entitlements"
  cp macos/Resources/Tandem.entitlements "$APP_ENTITLEMENTS"
  /usr/libexec/PlistBuddy \
    -c "Add :com.apple.developer.system-extension.install bool true" \
    -c "Add :com.apple.application-identifier string X8Y2M32MNQ.$BUNDLE_ID" \
    -c "Add :com.apple.developer.team-identifier string X8Y2M32MNQ" \
    -c "Add :com.apple.security.application-groups array" \
    -c "Add :com.apple.security.application-groups:0 string X8Y2M32MNQ.$BUNDLE_ID" \
    "$APP_ENTITLEMENTS" >/dev/null
fi

./scripts/mac-signing.sh sign "$APP" "$APP_ENTITLEMENTS"
echo "built $APP ($CONFIG, $VERSION)"
