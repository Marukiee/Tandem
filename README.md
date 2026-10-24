# Tandem

[![CI](https://img.shields.io/github/actions/workflow/status/Marukiee/Tandem/ci.yml?branch=main&label=CI)](https://github.com/Marukiee/Tandem/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Marukiee/Tandem?label=release)](https://github.com/Marukiee/Tandem/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](#license)
[![Rust](https://img.shields.io/badge/core-Rust-DEA584?logo=rust&logoColor=white)](#how-it-works)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#)
[![macOS](https://img.shields.io/badge/macOS-26%2B-000000?logo=apple&logoColor=white)](#)
[![Windows](https://img.shields.io/badge/Windows-experimental-0078D4?logo=windows&logoColor=white)](windows/README.md)
[![Linux](https://img.shields.io/badge/Linux-experimental-FCC624?logo=linux&logoColor=black)](#linux)

**The Apple ecosystem, on every device.** Tandem gives an Android phone, a Mac, a Linux device and a Windows device what
Apple's devices give each other: a second screen, one mouse for all of them, a shared clipboard, your phone on your
desktop, a hotspot that turns on by itself. Straight between your own devices, encrypted, with no account and no cloud.

It started as the KDE Connect alternative for people on a Mac, and grew into Continuity for the devices Apple leaves out.

> **Status: it works, and it grows fast.** Android and Mac are used the most. Windows and Linux are
> experimental and have been seen on few real machines so far. Each release says what is new, and
> `docs/PARITY.md` says per feature what works on which system.

## Highlights

- **Any device as an extra screen, several at once.** Your Mac's screen extends to a Linux device on the left and
  an Android phone on the right, each at its real sharpness. Tandem even **finds which side a device stands on by
  sound**: the computer plays a short sweep from its left and right speaker and the other device listens.
- **One mouse and keyboard for all your computers.** Push the pointer over the edge of the screen and it is on the
  next computer. Drag a file along, and on a Mac click where it should go, into a folder or a field of a website.
- **Your phone on your computer.** See its screen in a window and use it live with mouse and keyboard, use its
  camera, ring it, browse its files, and get its notifications and calls with replies from your desktop.
- **One clipboard.** Copy on one device, paste on the other, with a history. Passwords from a password manager stay out.
- **Never offline.** No Wi-Fi? Tandem asks your phone for its hotspot by itself, and keeps clipboard and
  notifications going over Bluetooth when there is no network at all.
- **Sound where you want it.** Your Mac plays through your phone, your phone through your Mac, and the sound of a
  screen you look at comes along, with a question when headphones are in.
- **What works, at a glance.** Every device says what it is ready for and what it still needs (a permission, a
  setting), and one button opens that setting on that device.

## Apple's Continuity, and Tandem

| On Apple's devices | In Tandem, on Mac, Android, Linux and Windows |
| --- | --- |
| Sidecar | Extend your screen to a phone, tablet or another computer (from a Mac to several at once), on the side where they stand |
| Universal Control | One mouse and keyboard over several computers, files dragged over the edge |
| iPhone Mirroring | Your phone's screen in a window, used live with mouse and keyboard |
| Universal Clipboard | One clipboard over all devices, with history |
| AirDrop | Send to any of your devices from the share menu, a drop zone or Quick Share, also to guests |
| Continuity Camera | Your phone's camera in a window on your computer, and a photo or scan straight into a document |
| Calls and notifications on the Mac | Your phone's notifications and calls on your computer, with replies |
| Instant Hotspot | Your phone's hotspot turned on from the computer, by itself when there is no network |
| Find My (ringing) | Ring your phone from the computer |
| AirPlay to a speaker | Your computer's sound through your phone, your phone's sound through your computer |
| Control Center | Quick buttons on the Mac's Control Center, Android's Quick Settings and the tray menu of Linux and Windows |

## Everything else

- **Share with one tap.** Take a screenshot, tap share, and all your devices are in the share menu
- **Remote desktop** between phone, Mac, Windows and Linux, asked for and allowed per device
- **Phone as trackpad, keyboard and remote**, with adjustable speed
- **SSH terminal**, your phone's files in Finder and in the Files app, Tailscale when it is needed
- **Updates without uninstalling**. The apps look for updates themselves

## Install

Everything is on the [releases page](https://github.com/Marukiee/Tandem/releases/latest). Each file has a
`.sha256` next to it if you want to check the download.

### Android (12 or newer)

1. Download `Tandem.apk` on your phone.
2. Open it. Android asks once whether the app you open it with (your browser or files app) may install
   apps from unknown sources. Turn that on, go back and tap Install.

After that you never need GitHub again. Tandem looks for a new version at most once a day and installs it
over the old one, without uninstalling. For that the app asks once for permission to install apps.

### Mac (macOS 26 or newer, Apple silicon)

1. Download `Tandem-macOS.zip`, unzip it and drag Tandem to Applications.
2. Open Tandem. macOS refuses the first time, because the app is signed with its own certificate and is not
   notarised by Apple. Go to System Settings, Privacy and Security, scroll down and click "Open Anyway" next
   to Tandem. You only do this once.

Updates install themselves. Every version is signed with the same certificate, so your permissions
(Bluetooth, local network, notifications, accessibility) stay.

### Windows (experimental)

1. Download `Tandem-Windows-x64-setup.exe` from the [latest release](https://github.com/Marukiee/Tandem/releases/latest).
2. Open it. Windows warns about an unknown publisher, because the installer is not signed yet: choose
   "More info" and then "Run anyway". You do not need to be an administrator.
3. If the firewall asks whether Tandem may communicate, choose "Private networks". Otherwise your phone does
   not find this PC.
4. Pair your phone with the QR code in the window.

It is new and has been used on few PCs, so expect rough edges. What works and what does not yet is in
[windows/README.md](windows/README.md).

### Linux

The app for Linux comes as a `.deb` (Ubuntu, Debian, Mint), an `.rpm` (Fedora, openSUSE) and an AppImage for
any other distribution. It is experimental, like the one for Windows, and works best on GNOME with Wayland.

`tandemd` is the same core as a service without a window, for a server or a Raspberry Pi. Installing it,
pairing and the systemd service are in [packaging/README.md](packaging/README.md). A device that is always on,
such as a server on your tailnet, is set up with the [Hub](hub/README.md).

## How it works

One Rust core does the network, the encryption and the transfers. Android, macOS, Windows and Linux are thin
shells around it.

- **QUIC with TLS 1.3**. Each device has one Ed25519 key. Devices trust each other's keys, no certificate
  authority. Files go over streams of their own, so a big file never holds up a notification, and a transfer
  that was cut off goes on where it stopped
- **The Circle**. Pair a new device once with a QR code and all the others know it by themselves. The list of
  members is a set of signed statements that devices pass on to each other
- **LAN and Tailscale**. On your own network devices find each other with mDNS, from afar over your tailnet.
  They try every address at once and keep the fastest

More in [docs/PROTOCOL.md](docs/PROTOCOL.md) and [docs/SECURITY.md](docs/SECURITY.md).

## Build

```bash
# test the core
cargo test --workspace

# the Android app (JDK 21, Android SDK 36, NDK 28, cargo-ndk)
./scripts/build-core-android.sh
(cd android && ./gradlew assembleDebug)

# the Mac app (the Command Line Tools with Swift 6.4 are enough)
./scripts/build-macos.sh
```

Making a release is described in [docs/RELEASING.md](docs/RELEASING.md).

## License

AGPL-3.0. See [LICENSE](LICENSE).
