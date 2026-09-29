#!/usr/bin/env bash
# Builds the Rust core for Android (arm64) and generates the Kotlin bindings.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"

PROFILE="${PROFILE:-release}"
FLAGS=(-t arm64-v8a -o android/app/src/main/jniLibs build -p tandem-core)
[ "$PROFILE" = "release" ] && FLAGS+=(--release)

# 16 KB page alignment, required for apps that target Android 15 and up.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cargo ndk "${FLAGS[@]}"

# Bindings are generated from a host build of the same crate.
cargo build -q -p tandem-core
LIB="target/debug/libtandem_core.dylib"
[ -f "$LIB" ] || LIB="target/debug/libtandem_core.so"
OUT="android/app/src/main/java"
cargo run -q -p uniffi-bindgen --bin uniffi-bindgen -- generate \
  --library "$LIB" --language kotlin --no-format --out-dir "$OUT"
echo "core ready: android/app/src/main/jniLibs/arm64-v8a/libtandem_core.so"
