# Tandem

[![Licentie](https://img.shields.io/badge/licentie-AGPL--3.0-blue)](#licentie)
[![Rust](https://img.shields.io/badge/core-Rust-DEA584?logo=rust&logoColor=white)](#hoe-het-werkt)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#)
[![macOS](https://img.shields.io/badge/macOS-26%2B-000000?logo=apple&logoColor=white)](#)

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

## Hoe het werkt

Eén Rust-core doet het netwerk, de versleuteling en de overdracht. Android, macOS
en Linux zijn dunne schillen eromheen.

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
./scripts/build-android.sh

# de Mac-app (Command Line Tools met Swift 6.4 is genoeg)
./scripts/build-macos.sh
```

## Licentie

AGPL-3.0. Zie [LICENSE](LICENSE).
