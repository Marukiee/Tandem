#!/usr/bin/env bash
# Builds what people on Linux download: the app as a .deb (Debian, Ubuntu), an .rpm (Fedora, openSUSE) and an AppImage (any), with a
# checksum each.
#
#     scripts/build-linux-app.sh 0.1.46
#
# The files land in dist-linux. Run it on Linux with the packages of the web view installed (see .github/workflows/linux.yml
# for the list); CI does it on Ubuntu. The version of the app is written into windows/src-tauri/tauri.conf.json first, so the
# package, the About page and the engine agree with the tag. The app is the same one as on Windows: the interface is
# shared, and the parts that are the system's own are answered by the Linux side (see the winsys crate).
set -euo pipefail
version="${1:?the version, like 0.1.46}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/dist-linux"
mkdir -p "$out"

cd "$root/windows"
python3 - "$version" <<'PY'
import json, sys
path = "src-tauri/tauri.conf.json"
conf = json.load(open(path))
conf["version"] = sys.argv[1]
json.dump(conf, open(path, "w"), indent=2)
PY
# The web view of Linux has no video decoder to speak of, so the app brings one (see windows/src-tauri/src/video.rs).
npx --yes "@tauri-apps/cli@2" build --ci --features native-video --bundles deb,rpm,appimage

bundle="src-tauri/target/release/bundle"
deb="$(ls "$bundle"/deb/*.deb | head -n1)"
appimage="$(ls "$bundle"/appimage/*.AppImage | head -n1)"
rpm="$(ls "$bundle"/rpm/*.rpm | head -n1)"
cp "$deb" "$out/Tandem-Linux-x64.deb"
cp "$rpm" "$out/Tandem-Linux-x64.rpm"
cp "$appimage" "$out/Tandem-Linux-x64.AppImage"
cp "$deb" "$out/Tandem-v$version-Linux-x64.deb"
cp "$rpm" "$out/Tandem-v$version-Linux-x64.rpm"
cp "$appimage" "$out/Tandem-v$version-Linux-x64.AppImage"
chmod +x "$out/Tandem-Linux-x64.AppImage" "$out/Tandem-v$version-Linux-x64.AppImage"
(cd "$out" && for name in Tandem-Linux-x64.deb Tandem-Linux-x64.rpm Tandem-Linux-x64.AppImage; do sha256sum "$name" > "$name.sha256"; done)
ls -l "$out"
