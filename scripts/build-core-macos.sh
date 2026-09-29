#!/usr/bin/env bash
# Builds the Rust core for macOS and generates the Swift bindings the app needs.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"

export MACOSX_DEPLOYMENT_TARGET=26.0
PROFILE="${PROFILE:-release}"
TARGET="aarch64-apple-darwin"
FLAGS=(--target "$TARGET" -p tandem-core)
[ "$PROFILE" = "release" ] && FLAGS+=(--release)

cargo build "${FLAGS[@]}"
OUT="${CARGO_TARGET_DIR:-target}/$TARGET/$PROFILE"

mkdir -p macos/Libs macos/Sources/tandem_coreFFI/include macos/Sources/TandemCore
cp "$OUT/libtandem_core.a" macos/Libs/

GEN="$(mktemp -d)"
cargo run -q -p uniffi-bindgen --bin uniffi-bindgen-swift -- \
  "$OUT/libtandem_core.dylib" "$GEN" --swift-sources --headers --modulemap \
  --module-name tandem_coreFFI --modulemap-filename module.modulemap

cp "$GEN/tandem_coreFFI.h" "$GEN/module.modulemap" macos/Sources/tandem_coreFFI/include/
cp "$GEN/tandem_core.swift" macos/Sources/TandemCore/tandem_core.swift
rm -rf "$GEN"
echo "core ready: macos/Libs/libtandem_core.a"
