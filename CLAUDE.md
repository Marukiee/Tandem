# Tandem, working notes

Vaste afspraken voor dit project. Lees dit voordat je iets aanpast.

## Schrijfstijl

- **Nooit em-dashes** (`—`). Ook geen en-dash als koppelteken. Gebruik een gewone
  komma, een punt, of haakjes. Dit geldt overal: code, comments, commits, README,
  UI-teksten, en antwoorden in de chat.
- Geen AI-riedels. Geen "moreover", "delve", "seamlessly", "it's important to note".
- Kort houden. Een zin die niets toevoegt gaat eruit.
- Comments in de code leggen uit **waarom**, niet wat. Als de regel zichzelf uitlegt
  staat er geen comment bij.
- Nederlands in de UI (`values-nl`, Nederlandse vertalingen op de Mac), Engels als
  basis (`values`).

## Wat dit is

Een KDE Connect-alternatief voor Android, macOS en Linux, en (experimenteel) Windows.
Eén Rust-core (`crates/tandem-core`) doet netwerk, versleuteling en overdracht.
`android/`, `macos/` en `windows/` zijn dunne schillen die de core aanroepen (UniFFI,
bij Windows rechtstreeks als Rust-crate).

```
crates/tandem-core     de core: identiteit, circle, QUIC, overdracht, plugins
crates/tandemd         headless daemon en CLI (Linux, Hub, tests)
crates/uniffi-bindgen  genereert de Kotlin- en Swift-bindings
android/               Kotlin, Compose Material 3 Expressive
macos/                 SwiftUI met Liquid Glass, gebouwd met SwiftPM
windows/               Tauri 2 (Rust + webview, UI in windows/ui), eigen Cargo-workspace
docs/                  PROTOCOL.md en SECURITY.md
scripts/               bouwen, signen, releasen
```

## Ontwerpbesluiten die niet opnieuw ter discussie staan

- **Vertrouwen is een sleutel, geen certificaat.** Elk apparaat heeft één Ed25519-
  sleutel, het apparaat-id is de hash ervan. TLS pint de sleutel. De dialer pint de
  verwachte sleutel, de acceptor beslist direct na de handshake of de sleutel in de
  circle zit. Voor die beslissing wordt niets verwerkt.
- **De circle is een verzameling ondertekende verklaringen** en lidmaatschap is een
  pure functie van die verzameling. Tijd is een Lamport-teller (`seq`), nooit de
  klok. Zie `circle.rs` voor de regels bij verwijderen.
- **Geen `SocketAddr` in berichten.** Serde's gebufferde deserialiser verwacht tekst
  voor iets dat CBOR compact schrijft. Adressen reizen als strings.
- **Byte-arrays altijd via `bytes_array`.** Anders schrijft CBOR 32 losse getallen.
- **Onbekende berichten worden overgeslagen, niet afgekeurd.** Nieuwe velden krijgen
  `#[serde(default)]`.
- **Bestanden worden opgehaald door de ontvanger** (pull). Die kent zijn eigen
  offset, bepaalt de gelijktijdigheid en kan hervatten. De afzender bedient alleen.
- **Klembord op Android** mag in de achtergrond niet gelezen worden. Daarom een
  tegel, een deelknop en een optionele Shizuku-modus. Nooit een omweg die stiekem
  het klembord uitleest.

## Designtaal

Zelfde taal als MarkMaaktAI (`github.com/Marukiee/MarkMaaktAI`, zie daar ook de
CLAUDE.md). Bij twijfel daar kijken.

**Android:** Google Sans Flex met ROND-as op 100, Material Symbols Rounded via
`TandemIcons`, gegroepeerde slabs (24dp buiten, 4dp binnen, 2dp ertussen), pure zwart
`#000000` met een ladder van donkergrijs, alles beweegt via `TandemMotion` (springs
voor positie en grootte, tweens voor kleur en opacity, nooit omgekeerd),
`bouncyClickable` overal met dezelfde dip (`0.96f`), één bewegende pill in navigatie,
laadanimatie is de pill uit het icoon.

**macOS:** Liquid Glass. `glassEffect`, `GlassEffectContainer` en `glassEffectID`
voor vormen die in elkaar overlopen, een menubalk-app met een glazen paneel, en
vensters met een glazen zijbalk. Beweging is spring-gebaseerd (`.spring`, `.bouncy`),
nooit lineair. Glas alleen op wat zweeft (paneel, knoppen, groepen), niet op de
inhoud zelf.

## Techniek

- Package: `nl.markmaaktmedia.tandem`, minSdk 31, compileSdk 36, arm64 only.
- macOS bundle-id: `nl.markmaaktmedia.Tandem`, minimaal macOS 26.
- JDK 21 voor Android (`JAVA_HOME` staat in `~/.zprofile`). Rust stable.
- Bouwen zonder Xcode: `swift build` met de Command Line Tools is genoeg. Xcode is
  alleen nodig voor een Liquid Glass-icoon (`.icon`) en app-extensies.
- GitHub: `Marukiee/Tandem`. De update-checker leest de releases-feed en de workflow
  hangt de bestanden onder vaste namen aan de release (`Tandem.apk`,
  `Tandem-macOS.zip`).

## Signing en updates (kern van het gebruiksgemak)

Bijwerken zonder verwijderen werkt alleen als elke build met dezelfde sleutel is
ondertekend.

- **Android:** release-sleutel op `~/keystores/tandem-release.jks`, wachtwoord in
  `tandem-release.password` ernaast, en dezelfde vier waarden als GitHub-secrets
  (`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`). **Die sleutel nooit kwijtraken.**
- **macOS:** een blijvend zelfgemaakt code-signing certificaat (geen Apple Developer
  ID), `~/keystores/tandem-mac-signing.p12` met wachtwoord ernaast, en als secrets
  `MACOS_CERT_P12_B64` en `MACOS_CERT_PASSWORD`. Een stabiele signing-identiteit is
  wat Bluetooth, lokaal netwerk, meldingen en toegankelijkheid laat blijven staan na
  een update. Zonder notarisatie vraagt de eerste installatie eenmalig "Toch openen".
- **Updates op de Mac** worden gecontroleerd met een Ed25519-handtekening
  (`TANDEM_UPDATE_ED25519_SECRET` in CI, de publieke sleutel zit in de app) en met
  een vergelijking van de signing-identiteit van de nieuwe en de draaiende app.
- Repo is **publiek**, anders geeft de GitHub API 404 en werkt de update-check niet
  zonder token.

## Releasen

- Alles groen, dan pas taggen (`vX.Y.Z`). Het versienummer komt uit de tag, je hoogt
  niets op. Zie `docs/RELEASING.md`.
- **Nooit `git add -A` met half af werk in de map.** CI compileert wat er gecommit is.
- Na het pushen van een tag: run afwachten met `gh run list --workflow=release.yml`
  en controleren dat de release er echt staat.

## Werkwijze

- Fase 1 vragen stellen, fase 2 doorbouwen zonder tussentijds te stoppen.
- Bij twijfel over een keuze: kies, bouw door, en zeg achteraf wat je koos.

## Bouwen, testen en werken met agents

In een verse werkmap (worktree) ontbreken de git-genegeerde bouwresultaten. Eerst de core bouwen:
`scripts/build-core-macos.sh` voor de Mac (geeft `macos/Libs/libtandem_core.a` en de Swift-bindings) en
`scripts/build-core-android.sh` voor Android (jniLibs en Kotlin-bindings). Dat kost enkele minuten.

- **Core:** `cargo test -p tandem-core`, `cargo build -p tandemd`. De tests zijn tijdsgevoelig: een enkele
  time-out terwijl er veel CPU gebruikt wordt (emulator, andere builds) is geen bewijs, twee keer opnieuw draaien.
- **Mac:** `cd macos && swift build -c debug --arch arm64`. Een app om te draaien:
  `VERSION=0.1.31 ./scripts/build-macos.sh debug` geeft `macos/dist/Tandem Dev.app`. Start die altijd met
  `open -n "macos/dist/Tandem Dev.app" --env TANDEM_DATA_DIR=<map> --env TANDEM_DEBUG_DIR=<map>` en nooit rechtstreeks
  (anders is de shell de verantwoordelijke voor TCC en crasht Bluetooth). Een signaal `SIGUSR1` schrijft PNG's van de
  vensters naar de debugmap. Glas en de zijbalk komen daar zwart of wit uit, de lay-out klopt wel. Verder
  `TANDEM_DEBUG_PAGE` (`shared`, `files`), `TANDEM_DEBUG_SETTINGS=<tab>` (settings in een eigen venster),
  `TANDEM_DEBUG_PANEL=1` en `TANDEM_DEBUG_NO_WINDOW=1`. Echte schermopnames van de Mac mogen niet.
  Nooit iets van een netwerkschijf aanraken (macOS laat dan wachten op toestemming), gebruik een alarm:
  `perl -e 'alarm N; exec @ARGV' ...`.
- **Android:** `export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 21)`, dan
  `cd android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug`. Emulators (AVD): `tandem_test`,
  `tandem_colors`, `tandem_onb`, `tandem_remote`. Elke agent gebruikt zijn eigen AVD en `adb -s <serial>`, nooit die
  van een ander. Starten: `$ANDROID_HOME/emulator/emulator -avd <naam> -no-snapshot-save -no-audio`. Rechten geven:
  `adb shell pm grant nl.markmaaktmedia.tandem.debug android.permission.CAMERA`,
  `adb shell appops set nl.markmaaktmedia.tandem.debug MANAGE_EXTERNAL_STORAGE allow`. Een koppellink openen:
  `adb shell "am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '<uri>' -p nl.markmaaktmedia.tandem.debug"`
  (een app die al in een circle zit kan niet bij een andere joinen, eerst `adb shell pm clear <pakket>`; de Mac
  schrijft zijn koppellink naar `pairing-uri.txt` in de debugmap, `tandemd pair-show` toont die van een daemon). Een
  systeemdialoog van de emulator (stylus) kan de app laten lijken te hangen. Schermfoto's: `adb exec-out screencap -p`.
- **Windows:** de UI staat in `windows/ui` en draait ook in een gewone browser met `mock.js`
  (`python3 -m http.server`). De hele app laat zich alleen in CI bouwen.
- **Schijfruimte:** een agent-werkmap met zijn eigen `target/` is gauw 5 GB, en de debug-mappen van `target/` en `windows/src-tauri/target/` groeien tot 30 GB zonder dat cargo ooit opruimt (het project stond op 150 GB). Draai `scripts/clean-build.sh` na een ronde met agents en af en toe tussendoor; `--check` laat zien wat het zou doen.
- **Agents:** werken in een eigen werkmap met eigen `target/`. Nooit pushen, taggen of `changelog.json` aanpassen,
  dat doet degene die de agents aanstuurt. Committen met expliciete paden (`git add <pad>`, nooit `-A`) en de
  Co-Authored-By-regel. Bestanden die iedereen aanraakt (`strings.xml`, `Localizable.strings`, `SettingsView.swift`,
  `EngineModel.swift`, `ffi.rs`, `Theme.kt`) zo min en zo klein mogelijk wijzigen en nieuwe schermen in eigen
  bestanden zetten, dan blijft samenvoegen eenvoudig. Rapporteer eerlijk wat bewezen is en wat niet.

## Sleutels

Alleen niet-geheime feiten. De geheimen zelf staan nooit in de repo of in een chat.

- **Android-keystore** `~/keystores/tandem-release.jks`, alias `tandem`, RSA 4096, 100
  jaar geldig. SHA-256 van het certificaat:
  `67:6D:63:50:65:53:B7:00:66:96:E5:37:4C:09:72:36:4A:14:F3:71:8E:48:E7:76:4E:85:5B:91:23:F5:30:C2`
- **Update-sleutel (Ed25519)** privé in `~/keystores/tandem-update-ed25519.secret`.
  Publieke sleutel, ook in `macos/update-public-key.txt` en in de app:
  `sbMlUsuXyctYz4zszEFtc7Uq6PmGKo+nuqh8+RMc6IU=`
- **Mac-certificaat** `~/keystores/tandem-mac-signing.p12` (identiteit "Tandem Signing").
  SHA-256 van het certificaat:
  `7c198032e83795a12a49f6170dee20e4b9dffd76901aa642174b1f7d3d03bebf`
- **Aanmaken** met `scripts/android-keystore.sh`, `swift scripts/gen-update-key.swift` en
  `scripts/mac-signing.sh setup`. Alle drie doen niets als de sleutel al bestaat.
- **GitHub-secrets** uploaden met `scripts/setup-secrets.sh` (alleen namen worden getoond).

**Maak een back-up van `~/keystores`.** Zonder de Android-sleutel kan niemand nog
bijwerken zonder eerst te verwijderen, zonder de update-sleutel accepteert geen
geïnstalleerde Mac-app nog een update, en zonder het Mac-certificaat vragen alle
rechten (Bluetooth, lokaal netwerk, toegankelijkheid) opnieuw om toestemming.
