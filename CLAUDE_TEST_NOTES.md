# Test notities voor Claude agents - Unified Call feature

## Wat er gebouwd is (2026-11-03)

### Core (Rust)
- `CallActionKind` in `proto.rs`: nieuwe variants `MoveToThisDevice`, `MoveBack`, `MuteMic`
- `TandemCallAction` in `ffi.rs`: UniFFI enum en beide `From` impls bijgewerkt
- Core build: `cargo test -p tandem-core` (slagen)

### Mac (Swift)
- `CallWindow.swift`: call cards met knoppen "Move here" / "Back to phone" + mute mic
- `tandem_core.swift`: nieuwe cases toegevoegd aan `TandemCallAction` enum (6, 7, 8)
- Knop wisselt tekst: "Naar hier verplaatsen" <-> "Call terugzetten" per klik
- Mute mic knop in elke call card

### Android (Kotlin)
- `CallMonitor.kt`: nieuwe action handlers voor MoveToThisDevice, MoveBack, MuteMic
- `routeAudioToDevice()`: audio routing naar Mac of lokaal
- `toggleMic()`: mic mute via AudioManager

## Wat te testen op de Mac

1. **Call cards verschijnen** bij inkomende/uitgaande calls
2. **"Move here" knop** - verplaatst call geluid naar Mac, wisselt naar "Back to phone"
3. **"Back to phone" knop** - zet geluid terug naar telefoon, wisselt naar "Move here"
4. **Mute mic knop** - dempt/undempt microfoon op dit apparaat
5. **Hang up knop** - beëindigt call
6. **Answer/Decline** tijdens ringing

## Wat te testen op Linux/Windows (na Mac verified)

- Dezelfde knoppen in call panelen
- Audio routing werkt naar Mac of lokaal
- Mute mic werkt overal

## Known issues / aandachtspunten

- `tandem_core.swift` is toegevoegd aan git (was in .gitignore) - controleer dat bindings correct gegenereerd zijn
- Linux: audio routing via PipeWire nog niet geïmplementeerd
- Windows: Phone Link HFP route nog te bouwen
- Android: Shizuku audio routing vereist

## Commando's voor build en test

```bash
# Core
cargo check -p tandem-core
cargo test -p tandem-core

# Mac bindings (na veranderingen in ffi.rs/proto.rs)
scripts/build-core-macos.sh

# Mac app
cd macos && swift build -c debug --arch arm64

# Android
export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd android && ./gradlew --console=plain -q :app:testDebugUnitTest :app:assembleDebug

# Linux check
scripts/check-linux.sh
```
