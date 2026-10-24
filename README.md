# Tandem

[![CI](https://img.shields.io/github/actions/workflow/status/Marukiee/Tandem/ci.yml?branch=main&label=CI)](https://github.com/Marukiee/Tandem/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Marukiee/Tandem?label=release)](https://github.com/Marukiee/Tandem/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](#license)
[![Rust](https://img.shields.io/badge/core-Rust-DEA584?logo=rust&logoColor=white)](#how-it-works)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#)
[![macOS](https://img.shields.io/badge/macOS-26%2B-000000?logo=apple&logoColor=white)](#)
[![Windows](https://img.shields.io/badge/Windows-experimental-0078D4?logo=windows&logoColor=white)](windows/README.md)
[![Linux](https://img.shields.io/badge/Linux-experimental-FCC624?logo=linux&logoColor=black)](#linux)

Your phone and your computers as one. Files, clipboard, notifications and hotspot go
straight between your own devices, encrypted, with no account and no server.

Meant as the KDE Connect alternative for people on a Mac: the same idea, with a protocol
of its own that is fast, reliable and secure, and that keeps working with Linux and Windows.

> **Status: it works, and it grows fast.** Android and Mac are used the most. Windows and Linux are
> experimental and have been seen on few real machines so far. Each release says what is new, and
> `docs/PARITY.md` says per feature what works on which system.

## What it does

- 📤 **Share with one tap**. Take a screenshot, tap share and all your devices are in the share menu
- 📋 **A clipboard that comes along**, with history. Passwords from a password manager stay out
- 🔔 **Your phone's notifications on your computer**, with replies from your desktop and calls to answer or decline
- 🖥️ **Screen sharing and control** between phone, Mac, Windows and Linux, like a remote desktop
- 🪟 **Second screen**. A phone or computer becomes an extra screen of another computer, asked for from either side
- 🧭 **Finds the side by sound**, so a second screen and the shared mouse sit where the device really stands
- 🖱️ **One mouse and keyboard over several computers**, with files dragged over the edge
- 📱 **Phone as trackpad, keyboard and remote**, with adjustable speed
- 📶 **Automatic hotspot** and **Bluetooth** for clipboard and notifications when there is no network
- 🔊 **Your phone's sound** on the computer and the other way round
- ✅ **What works**, per device: what is missing (a permission, a setting) and a button that opens it on that device
- 🖧 **SSH terminal**, Quick Share, your phone's files, Tailscale when it is needed
- 🔁 **Updates without uninstalling**. The app looks for updates itself

Everything goes straight between your own devices, encrypted, with no account.

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
