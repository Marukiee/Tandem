#!/bin/zsh
# Type checks the Windows build of the Tauri app on a Mac, the way check-linux.sh does for Linux: nothing is linked, a stand-in C compiler
# makes empty C parts. It proves the Rust code compiles for Windows, in a minute instead of a run on GitHub; it does not prove that it runs.
set -e
here=${0:A:h}
cd "$here/../windows/src-tauri"
PATH="$here/linux-check:$PATH" CC_x86_64_pc_windows_msvc="$here/linux-check/fake-cc" CXX_x86_64_pc_windows_msvc="$here/linux-check/fake-cc" \
  AR_x86_64_pc_windows_msvc="$here/linux-check/fake-cc" \
  cargo check --offline --target x86_64-pc-windows-msvc "$@"
