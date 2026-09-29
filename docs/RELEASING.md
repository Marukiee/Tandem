# Een release maken

## In het kort

1. Zorg dat `main` groen is: de [CI](../.github/workflows/ci.yml) slaagt en de apps
   bouwen.
2. Push een tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. Wacht tot de run klaar is en controleer dat de release er staat:

   ```bash
   gh run list --workflow=release.yml
   gh release view v0.2.0
   ```

Je hoogt nergens een versie op. Alles komt uit de tag: de Android `versionName` is
`0.2.0`, de `versionCode` is `major * 10000 + minor * 100 + patch` (dus 200) en de Mac-app
krijgt dezelfde versie en dezelfde build. Houd minor en patch onder 100, anders klopt
die berekening niet meer en weigert de workflow het. Een tag met een suffix, zoals
`v0.3.0-rc1`, wordt een pre-release en telt niet als "laatste", dus geen enkele app
biedt hem aan als update.

Zonder te publiceren proberen kan ook: Actions, Release, Run workflow, een tag invullen
en "publish" uit laten staan. Je krijgt dan de bestanden als download bij de run.

## Wat CI doet

De workflow [release.yml](../.github/workflows/release.yml) draait vier bouwjobs en
publiceert pas als alle vier slagen.

| Job | Resultaat |
| --- | --- |
| Android | Getekende APK. Faalt als hij niet met de release-sleutel is getekend. |
| Mac | App gebouwd, getekend met het Mac-certificaat, gezipt en van een Ed25519-handtekening voorzien |
| Linux | `tandemd` voor x86_64 en aarch64, elk op een eigen runner, gebouwd op Ubuntu 22.04 zodat hij ook op Debian 12 en de Raspberry Pi draait |
| GitHub Release | Zet alle bestanden onder de vaste namen aan de release, met automatisch gegenereerde notities |

Bestanden in de release:

- `Tandem.apk`, `Tandem.apk.sha256` en `Tandem-vX.Y.Z.apk` voor Android
- `Tandem-macOS.zip`, `Tandem-macOS.zip.sha256` en `Tandem-macOS.zip.sig` voor de Mac
- `tandemd-linux-x86_64.tar.gz` en `tandemd-linux-aarch64.tar.gz`, elk met een `.sha256`
  en een kopie met het versienummer in de naam

**Blijf deze namen precies zo houden.** De apps zoeken erop. De notities zijn wat GitHub
zelf genereert. Wil je ze aanpassen, doe dat na afloop met
`gh release edit v0.2.0 --notes "..."`, de apps tonen ze in het updatevenster.

`tandemd --version` toont de versie uit `Cargo.toml`, niet die van de tag. De versie in
de bestandsnaam van het archief is wel goed.

## Hoe de update-check werkt

Alle apps lezen `https://api.github.com/repos/Marukiee/Tandem/releases/latest`. Dat werkt
zonder token zolang de repo **publiek** blijft.

- **Android** controleert hooguit één keer per dag bij het openen, en als je op "Nu
  controleren" tikt. Het vindt `Tandem.apk`, vergelijkt de tag met de eigen versie, laat
  de gebruiker kiezen, downloadt, vergelijkt de SHA-256 met `Tandem.apk.sha256` en
  installeert via PackageInstaller. Android accepteert het alleen als de nieuwe APK met
  dezelfde sleutel is getekend. Dat is ook de beveiliging.
- **Mac** doet hetzelfde met `Tandem-macOS.zip`. Daarna controleert hij de SHA-256, de
  Ed25519-handtekening over de zip (publieke sleutel in `Info.plist`, sleutel
  `TandemUpdatePublicKey`), `codesign --verify`, en dat het certificaat van de nieuwe app
  hetzelfde is als dat van de draaiende app. Pas dan vervangt een klein script de app en
  start hem opnieuw.
- **Linux** werkt zichzelf niet bij. Nieuwe `tar.gz` downloaden, `tandemd` vervangen,
  `systemctl --user restart tandemd`. De Hub: `git pull` en `docker compose up -d --build`.

## Sleutels

Alles staat in `~/keystores` en de secrets in GitHub komen daar vandaan
(`scripts/setup-secrets.sh`). De vingerafdrukken staan in [CLAUDE.md](../CLAUDE.md).

**Maak een back-up van `~/keystores` en raak die map nooit kwijt.** Elke sleutel is een
belofte aan al je gebruikers. Zonder de sleutel kun je die belofte niet meer nakomen:

| Sleutel | Wat er gebeurt als hij weg is of wordt vervangen |
| --- | --- |
| Android-keystore | Android weigert elke update. Iedereen moet de app verwijderen en opnieuw installeren. |
| Mac-certificaat | De app weigert de update ("ondertekend door iemand anders") en alle toestemmingen worden opnieuw gevraagd. Iedereen moet de nieuwe versie met de hand installeren. |
| Update-sleutel (Ed25519) | Geen geïnstalleerde Mac-app accepteert nog een update. Iedereen moet met de hand opnieuw installeren. |

Een sleutel vervangen kan dus alleen als je bewust iedereen laat herinstalleren, bijvoorbeeld
omdat hij gelekt is. Dan:

1. Maak de nieuwe sleutel: `scripts/android-keystore.sh`, `swift scripts/gen-update-key.swift`
   of `scripts/mac-signing.sh setup`. Ze doen niets als de sleutel al bestaat, dus zet
   de oude eerst apart (niet weggooien).
2. Ander Android-certificaat: werk `EXPECTED_SHA256` bij in
   `.github/workflows/release.yml`. Andere update-sleutel: zet de nieuwe publieke sleutel in
   `macos/update-public-key.txt`.
3. Upload de secrets opnieuw met `scripts/setup-secrets.sh`, werk CLAUDE.md bij en zeg in
   de release-notities dat mensen eenmalig met de hand moeten installeren.
