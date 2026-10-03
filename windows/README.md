# Tandem for Windows

**Experimental.** It builds and its parts are tested, but it has not been run on many PCs yet, so expect rough
edges. The installers come from the release called "Windows preview (experimental)".

A thin shell around the same Rust core the phone and the Mac use (`crates/tandem-core`), built with
[Tauri 2](https://tauri.app): the engine, the clipboard, the notifications and the tray icon are Rust, the
two windows (the main window and the small panel above the tray icon) are plain HTML, CSS and JavaScript
in `ui/`, with Preact and htm vendored, so there is no `npm install` and no build step for the interface.

```
src-tauri/   the Rust side: starts the engine, turns its events into notifications and lists, commands for the windows
ui/          the interface; open ui/index.html through any static server to look at it with pretend data
```

## Looking at the interface without Windows

The interface falls back to a pretend app (`ui/js/mock.js`) when it is not inside Tandem, so it can be developed
and looked at in any browser:

```bash
cd windows/ui && python3 -m http.server 8765
# http://localhost:8765/index.html            the main window
# http://localhost:8765/index.html?mock=empty a PC with nothing paired yet
# http://localhost:8765/index.html?page=settings  a page by name: device, shared, notifications, settings
# http://localhost:8765/panel.html            the panel above the tray icon (352 points wide)
```

## Building

On Windows (CI does exactly this, see `.github/workflows/windows.yml`):

```powershell
pwsh scripts/build-windows.ps1 -Version 0.1.26
```

That writes `dist-windows/Tandem-Windows-x64-setup.exe` (per-user installer, no administrator needed) and
`tandemd-windows-x86_64.zip`. The Rust side alone can be checked on any system with `cargo check` in `src-tauri`.

## What works, and what does not yet

Works (compiled and unit tested, but see the last line): pairing by code, files both ways, clipboard both ways,
phone notifications as Windows notifications, the tray panel, music of the phone shown with buttons, the phone's
trackpad and keyboard on this PC (off until you turn it on in the settings), settings, Dutch and English.

Not yet: the phone's music as the system media overlay, the hotspot, Bluetooth, sound to the phone, automatic
updates, folders, replying to notifications.

`Tandem.exe --minimized` starts it in the tray (what starting with Windows does) and `Tandem.exe --panel` opens
the small panel in the corner of the screen, which is how CI takes a picture of it.

Nothing here has been run on a real Windows PC yet by the people who wrote it. The installer is not signed, so
Windows SmartScreen asks once whether to run it.
