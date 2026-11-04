# Tandem

[![Release](https://img.shields.io/github/v/release/Marukiee/Tandem?label=release)](https://github.com/Marukiee/Tandem/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#installeren)
[![macOS](https://img.shields.io/badge/macOS-26%2B-000000?logo=apple&logoColor=white)](#installeren)

[English](README.md) | Nederlands

**Continuity voor elk apparaat.** Tandem geeft een Android-telefoon, een Mac, een Linux-apparaat en een Windows-apparaat wat Apple-apparaten elkaar geven: een tweede scherm, één muis voor allemaal, een gedeeld klembord, je telefoon op je computer en een hotspot die vanzelf aangaat. Rechtstreeks tussen je eigen apparaten, versleuteld, zonder account en zonder cloud.

Het begon als het KDE Connect-alternatief voor mensen op een Mac en groeide uit tot Continuity voor de apparaten die Apple overslaat.

> **Stand: het werkt en het groeit snel.** Android en Mac worden het meest gebruikt. Windows en Linux zijn experimenteel en zijn nog op weinig echte machines gezien. Elke release zegt wat er nieuw is, en `docs/PARITY.md` zegt per functie wat op welk systeem werkt.

## Wat het doet

- **Elk apparaat als extra scherm, meerdere tegelijk.** Het scherm van je Mac loopt door naar een Linux-apparaat links en een Android-telefoon rechts, elk op zijn eigen scherpte. Tandem vindt zelfs **aan het geluid welke kant** een apparaat staat
- **Eén muis en toetsenbord voor al je computers.** Duw de aanwijzer over de rand van het scherm en hij staat op de volgende computer. Sleep een bestand mee
- **Je telefoon op je computer.** Zie zijn scherm in een venster en bedien hem live met muis en toetsenbord, gebruik zijn camera, laat hem overgaan, blader door zijn bestanden en krijg zijn meldingen en gesprekken, met antwoorden vanaf je computer
- **Eén klembord.** Kopieer op het ene apparaat, plak op het andere, met geschiedenis. Wachtwoorden uit een wachtwoordmanager blijven erbuiten
- **Nooit offline.** Geen wifi? Tandem vraagt je telefoon vanzelf om zijn hotspot, en houdt klembord en meldingen draaiende over Bluetooth als er helemaal geen netwerk is
- **Geluid waar je het wilt.** Je Mac speelt via je telefoon, je telefoon via je Mac, en het geluid van een scherm waar je naar kijkt gaat mee
- **Delen met één tik.** Maak een screenshot, tik op delen, en al je apparaten staan in het deelmenu

## Installeren

Alles staat op de [releasepagina](https://github.com/Marukiee/Tandem/releases/latest). Bij elk bestand staat een `.sha256` als je de download wilt controleren.

### Android (12 of nieuwer)

1. Download `Tandem.apk` op je telefoon.
2. Open het bestand. Android vraagt één keer of de app waarmee je het opent (je browser of bestanden-app) apps van onbekende bronnen mag installeren. Zet dat aan, ga terug en tik op Installeren.

Daarna heb je GitHub nooit meer nodig: Tandem zoekt hooguit één keer per dag naar een nieuwe versie en installeert die over de oude heen. Je kunt de repository ook in [Obtainium](https://obtainium.imranr.dev) zetten.

### Mac (macOS 26 of nieuwer, Apple silicon)

1. Download `Tandem-macOS.zip`, pak hem uit en sleep Tandem naar Programma's.
2. Open Tandem. macOS weigert de eerste keer, omdat de app met een eigen certificaat is ondertekend en niet door Apple is genotariseerd. Ga naar Systeeminstellingen, Privacy en beveiliging, scroll omlaag en klik op "Toch openen" naast Tandem. Dat doe je maar één keer.

Updates installeren zichzelf. Elke versie is met hetzelfde certificaat ondertekend, dus je rechten (Bluetooth, lokaal netwerk, meldingen, toegankelijkheid) blijven.

### Windows en Linux (experimenteel)

Windows heeft een installatieprogramma (`Tandem-Windows-x64-setup.exe`), Linux een `.deb`, een `.rpm` en een AppImage. Stap voor stap staat het in de [Engelse README](README.md#windows-experimental) en in [windows/README.md](windows/README.md).

## Bouwen en meer

Bouwinstructies, hoe het werkt (QUIC, TLS 1.3, de Circle) en hoe je een release maakt staan in de [Engelse README](README.md#how-it-works), [docs/PROTOCOL.md](docs/PROTOCOL.md) en [docs/RELEASING.md](docs/RELEASING.md).

## Licentie

AGPL-3.0, zie [LICENSE](LICENSE). Gemaakt door Mark Maakt Media.
