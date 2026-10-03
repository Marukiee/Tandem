# Tandem

[![CI](https://img.shields.io/github/actions/workflow/status/Marukiee/Tandem/ci.yml?branch=main&label=CI)](https://github.com/Marukiee/Tandem/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Marukiee/Tandem?label=release)](https://github.com/Marukiee/Tandem/releases/latest)
[![Licentie](https://img.shields.io/badge/licentie-AGPL--3.0-blue)](#licentie)
[![Rust](https://img.shields.io/badge/core-Rust-DEA584?logo=rust&logoColor=white)](#hoe-het-werkt)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#)
[![macOS](https://img.shields.io/badge/macOS-26%2B-000000?logo=apple&logoColor=white)](#)
[![Windows](https://img.shields.io/badge/Windows-experimenteel-0078D4?logo=windows&logoColor=white)](windows/README.md)
[![Linux](https://img.shields.io/badge/Linux-tandemd-FCC624?logo=linux&logoColor=black)](packaging/README.md)

Je telefoon en je computers als één geheel. Bestanden, klembord, meldingen en
hotspot gaan direct tussen je eigen apparaten, versleuteld, zonder account en
zonder server.

Bedoeld als het alternatief voor KDE Connect voor wie op een Mac zit: dezelfde
gedachte, maar met een eigen protocol dat snel, betrouwbaar en veilig is, en dat
ook met Linux blijft werken.

> **Status: in aanbouw.** De Rust-core komt eerst, daarna de Android-app en de
> Mac-app. Wat hieronder staat is het plan, niet wat er al is. Zodra iets werkt
> staat het in de changelog van de release.

## Wat het gaat doen

- 📤 **Delen met één tik**. Maak een screenshot, tik op delen en al je
  apparaten staan als doel in het deelmenu, ook als er drie tegelijk verbonden zijn
- 📋 **Klembord dat meebeweegt**. Tekst, links en afbeeldingen, en wachtwoorden uit
  een wachtwoordmanager blijven eruit
- 🔔 **Meldingen op je Mac**, met antwoorden vanaf je bureaublad. Een code uit een
  sms staat meteen op je klembord
- 📞 **Oproepen** zien op je Mac, opnemen of weigeren
- 📶 **Automatische hotspot**. Heeft je Mac geen verbinding, dan zet je telefoon
  zijn hotspot aan en de Mac meldt dat hij die gebruikt
- 🖱️ **Telefoon als trackpad, toetsenbord en afstandsbediening**
- 🔋 **Batterij en status** van je telefoon in de menubalk
- 🔁 **Bijwerken zonder verwijderen**. De app controleert zelf op updates en
  installeert ze over de vorige heen

## Installeren

Alles staat op de [releases-pagina](https://github.com/Marukiee/Tandem/releases/latest).
Bij elk bestand staat een `.sha256` als je de download wilt controleren.

### Android (12 of nieuwer)

1. Download `Tandem.apk` op je telefoon.
2. Open het bestand. Android vraagt eenmalig of de app waarmee je het opent (je browser
   of bestanden-app) apps van onbekende bronnen mag installeren. Zet dat aan, ga terug
   en tik op Installeren.

Daarna hoef je niet meer naar GitHub. Tandem controleert zelf hooguit één keer per dag
of er een nieuwe versie is en installeert die over de vorige heen, zonder verwijderen.
Daarvoor vraagt de app één keer toestemming om apps te installeren.

### Mac (macOS 26 of nieuwer, Apple Silicon)

1. Download `Tandem-macOS.zip`, pak uit en sleep Tandem naar Programma's.
2. Open Tandem. macOS weigert dat de eerste keer, omdat de app is ondertekend met een
   zelfgemaakt certificaat en niet is genotariseerd door Apple. Ga naar Systeeminstellingen,
   Privacy en beveiliging, scroll naar onder en klik bij Tandem op "Toch openen". Dat
   hoef je maar één keer te doen.

Updates installeren zichzelf. Omdat elke versie met hetzelfde certificaat is
ondertekend, blijven je toestemmingen (Bluetooth, lokaal netwerk, meldingen,
toegankelijkheid) staan.

### Windows (experimenteel)

1. Download `Tandem-Windows-x64-setup.exe` van de [nieuwste release](https://github.com/Marukiee/Tandem/releases/latest).
2. Open het bestand. Windows waarschuwt voor een onbekende uitgever, omdat het installatieprogramma nog niet
   is ondertekend: kies "Meer informatie" en dan "Toch uitvoeren". Je hebt geen beheerder nodig.
3. Vraagt de firewall of Tandem mag communiceren, kies dan "Privénetwerken". Anders vindt je telefoon deze pc niet.
4. Koppel je telefoon met de QR-code in het venster.

Het is nieuw en nog op weinig pc's gebruikt, dus verwacht rafelige randjes. Wat werkt en wat nog niet staat
in [windows/README.md](windows/README.md). Tussen versies door staat er een voorbeeldbouw op de release
`windows-preview`.

### Linux en de Hub

`tandemd` draait als achtergronddienst op Linux. Installeren, koppelen en de systemd-dienst
staan in [packaging/README.md](packaging/README.md). Een apparaat dat altijd aan staat,
zoals een server op je tailnet, zet je op met de [Hub](hub/README.md).

## Hoe het werkt

Eén Rust-core doet het netwerk, de versleuteling en de overdracht. Android, macOS,
Windows en Linux zijn dunne schillen eromheen.

- **QUIC met TLS 1.3**. Elk apparaat heeft één Ed25519-sleutel. Apparaten
  vertrouwen elkaars sleutel, geen certificaatautoriteit. Bestanden gaan over
  eigen streams, dus een groot bestand houdt een melding niet op, en een
  onderbroken overdracht gaat verder waar hij bleef
- **De Circle**. Koppel een nieuw apparaat één keer met een QR-code en alle andere
  apparaten weten het vanzelf. De ledenlijst bestaat uit ondertekende
  verklaringen die apparaten onderling doorgeven
- **LAN en Tailscale**. Op je eigen netwerk vinden apparaten elkaar via mDNS, op
  afstand via je tailnet. Ze proberen alle adressen tegelijk en houden de snelste
- **Linux mogelijk**. Dezelfde core draait als achtergronddienst op Linux, en later
  komt er een brug voor het protocol van KDE Connect

Meer in [docs/PROTOCOL.md](docs/PROTOCOL.md) en [docs/SECURITY.md](docs/SECURITY.md).

## Bouwen

```bash
# de core testen
cargo test --workspace

# de Android-app (JDK 21, Android SDK 36, NDK 28, cargo-ndk)
./scripts/build-core-android.sh
(cd android && ./gradlew assembleDebug)

# de Mac-app (Command Line Tools met Swift 6.4 is genoeg)
./scripts/build-macos.sh
```

Een release maken staat in [docs/RELEASING.md](docs/RELEASING.md).

## Licentie

AGPL-3.0. Zie [LICENSE](LICENSE).
