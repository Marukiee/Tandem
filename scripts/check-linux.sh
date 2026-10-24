#!/bin/zsh
# Type checks the Linux build of the Tauri app on a Mac. The Linux system libraries (GTK, WebKit, D-Bus, PipeWire) are not here, but
# `cargo check` links nothing: a stand-in pkg-config says every library is there, and a stand-in C compiler makes empty objects for the
# C parts (ring, openh264). What it proves is that the Rust code compiles for Linux; it does not prove that it runs.
set -e
here=${0:A:h}
cd "$here/../windows/src-tauri"
PKG_CONFIG="$here/linux-check/fake-pkg-config" PKG_CONFIG_ALLOW_CROSS=1 \
  CC_x86_64_unknown_linux_gnu="$here/linux-check/fake-cc" CXX_x86_64_unknown_linux_gnu="$here/linux-check/fake-cc" \
  AR_x86_64_unknown_linux_gnu=ar \
  cargo check --offline --target x86_64-unknown-linux-gnu "$@"
