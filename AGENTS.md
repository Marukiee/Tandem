# Tandem, for the coding model

Read this whole file before every task. It is the short version of CLAUDE.md; only look things up in CLAUDE.md or docs/ when a task
needs them.

## What Tandem is

A KDE Connect alternative made by Mark: phone and computers in one circle. Files, clipboard, notifications, calls, screen sharing
and remote control, a shared mouse and keyboard, the phone's hotspot for the Mac, keep awake, unlock from afar.

One Rust core does the network, encryption and transfers. The apps are thin shells around it:

| Folder | What | Language |
| --- | --- | --- |
| `crates/tandem-core` | the core: identity, circle, QUIC, transfers, the messages between devices | Rust |
| `crates/tandemd` | headless daemon and CLI | Rust |
| `crates/uniffi-bindgen` | makes the Kotlin and Swift bindings of the core | Rust |
| `macos/` | the Mac app, SwiftUI, built with SwiftPM (no Xcode project) | Swift |
| `android/` | the Android app, Jetpack Compose | Kotlin |
| `windows/` | the Linux and Windows app: Tauri 2, UI in `windows/ui` (plain JS) | Rust + JS |
| `hub/` | Docker setup of the Hub: an always-on device in the circle (a server, a Pi) that takes files | Docker |
| `docs/` | `PROTOCOL.md`, `PARITY.md` (what works where), `ROADMAP.md` (the wish list), feature notes | |

Never read or search in `target/`, `dist/`, `build/`, `node_modules/`, `macos/.build/`, `windows/src-tauri/target/`, or the generated
bindings `android/app/src/main/java/uniffi/` and `macos/Sources/TandemCore/`. They are huge and made by tools.

## How a feature goes through the code

1. A message between devices is a variant of `Msg` in `crates/tandem-core/src/proto.rs`. Status fields are in `Status` there.
2. `crates/tandem-core/src/session.rs` turns an incoming message into an `Event` (`events.rs`).
3. `crates/tandem-core/src/ffi.rs` is what the apps see: `TandemEvent`, `TandemStatus`, and functions on `TandemEngine`.
4. Each app handles the event: Mac in `macos/Sources/Tandem/Model/EngineModel.swift`, Android in
   `android/.../engine/TandemService.kt` or `EngineHost.kt`, Linux/Windows in `windows/src-tauri/src/events.rs`.

Grep for an existing feature (for example `KeepAwake`) and copy how it goes through all four steps.

## Hard rules

- **Never an em-dash or en-dash** anywhere: code, comments, commits, UI text. Use a comma, a period or brackets.
- UI text: English is the base. Dutch goes in `macos/Resources/nl.lproj/Localizable.strings`, `android/app/src/main/res/values-nl/`,
  and the `nl` lines in `windows/ui/js/i18n.js` and `windows/src-tauri/src/i18n.rs`. Every new string gets its Dutch line.
- **Every feature on every device**: Mac, Android, Linux and Windows, in every role. When it really cannot work on one, write the
  reason in `docs/PARITY.md`. Something that cannot work is grey and says why when it is used, never a permanent warning.
- New fields in a message get `#[serde(default)]` in `proto.rs`, and in `ffi.rs` records `#[uniffi(default = None)]`. Old devices
  must keep working: unknown messages are skipped, never refused.
- Byte arrays in messages go through `bytes_array`. Addresses travel as strings, never `SocketAddr`.
- Comments say **why**, not what. Match the style and comment density of the file you are in.
- Keep UI consistent with its neighbours: same size, shape, spacing and hover label as the buttons next to it.
- Do not add dependencies (crates, Gradle libraries, Swift packages) without asking Mark.
- Do not touch keystores, secrets, signing or `scripts/setup-secrets.sh`.
- Do not change system settings of the computer, and do not install packages.

## Build and test

After every change, build what you touched. Fix errors before going on.

- **Core**: `cargo test -p tandem-core` (tests are timing sensitive: one timeout while the CPU is busy is not a failure, run again).
- **Bindings**: after changing `ffi.rs`, run `scripts/build-core-macos.sh` (Mac) and `scripts/build-core-android.sh` (Android).
  Each takes a few minutes. Without this the apps do not see your change.
- **Mac**: `cd macos && swift build -c debug --arch arm64`
- **Android**:
  `export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
  then `cd android && ./gradlew --console=plain -q :app:testDebugUnitTest :app:assembleDebug`
- **Linux code on this Mac**: `scripts/check-linux.sh` (a `cargo check` of the Tauri app for Linux; compiles, does not run).
- **Windows**: only builds in CI on GitHub.

An exhaustive `switch`/`when`/`match` on a core enum breaks in every app when you add a variant: build all of them.

## Committing and releasing

- Commit with explicit paths: `git add <file> <file>`, **never** `git add -A` or `git add .`.
- End every commit message with: `Co-Authored-By` naming the model that wrote it.
- Add a very short entry at the top of `changelog.json` (English and Dutch, one line per change). Never write that something is
  untested.
- Push and tag only when Mark asks. Pushing while the previous CI run is busy cancels it. A release is a tag `vX.Y.Z` on a commit
  whose CI (and "Linux app", "Windows app" when `windows/` changed) is green. See `docs/RELEASING.md`.
- New wishes from Mark go on `docs/ROADMAP.md`.

## Working method for this model

- Start by reading: `grep -rn` and file globbing to find the place. Read large files in parts (they are often over 1000 lines).
- Make small, exact edits. Never rewrite a whole file.
- One step at a time: edit, build, then the next step. Do not plan ten steps ahead without building.
- Rust with UniFFI, lifetimes or async that does not compile after two tries: stop and tell Mark what is wrong.
- Not sure what Mark wants, or about to delete or overwrite something: ask first.
- Answer Mark in Dutch, short.

## Where things stand

The version is in `changelog.json` (top entry). Open work and ideas: `docs/ROADMAP.md`. What works on which system: `docs/PARITY.md`.
Feature details: `docs/SCREEN.md`, `docs/HOTSPOT.md`, `docs/FILES.md`, `docs/INPUT_SHARING.md`, `docs/PROTOCOL.md`.
