# Volgende fase

Alles wat nog niet af is, op volgorde van aanpak. Begin bovenaan. Een punt is pas af als het op echte
toestellen is gezien, niet alleen gecompileerd. Zie CLAUDE.md voor de werkwijze en de agentregels.

## 0. Eerst op echte toestellen bekijken (kost weinig, voorkomt bouwen op zand)

- Je Mac bedienen vanuit Android, van begin tot eind (Schermopname en Toegankelijkheid, herstart na
  toestemming, prompt op de Mac, aanraken, slepen, scrollen, opnieuw verbinden).
- Telefoonscherm en telefooncamera in een Mac-venster (toestemmingsdialoog van Android, de encoder
  op de Xperia, SPS en PPS komen apart bij sommige encoders).
- Invoegen vanaf je telefoon: de documentscanner van Google op een echte telefoon.
- Android Bestanden-app met de Mac en pc erin (DocumentsProvider is nooit live gedraaid).
- Klembordpaneel op de Mac: sneltoets, plakken in de app ervoor, uiterlijk van het glas.
- Mac Bestanden: vasthouden en slepen om te selecteren, vinkjes, zoeken, filter.
- Android: Trackpad-pagina, hotspotkaart met maanden en de dagkeuze, Uiterlijk en taal.
- Hold-to-drag en snelheid op de Xperia afstellen, golvende zoekbalk (efficientie opnieuw meten).
- Updatebanner op Android overlapt de paginakoppen.

- Gedaan in 0.1.40 en 0.1.41, nog niet op echte toestellen gezien: de kaart Deze Mac bedienen met stappen, de
  twee-vingers-rechtsklik, de verbonden knoppen in de weergave, het Android-scherm dat bijblijft op de Mac
  (de encoder herhaalt het beeld elke 100 ms en een wachter vraagt een keyframe als er 0,7 s niets uitkomt), de Android-toegankelijkheidsservice voor het bedienen
  van de telefoon vanaf de Mac (tikken, slepen, scrollen, typen, rechterknop is Terug), de kortere deelmelding.
  De Android-weergave is wel getest op een emulator tegen een nagebootste Mac (`tandemd --pretend-screen`): het beeld speelt,
  een tik is een klik, slepen beweegt de aanwijzer.
- Vraag aan Mark: welke knoppen onderin het venster van de telefoon op de Mac werken niet? (Alle knoppen staan in
  `Live/LiveView.swift`, bij het kopieren en draaien is het beeld nodig.)
- Windows Rust-job in CI viel eenmaal om op `the_picture_comes_back_tagged_with_the_request` (tijdgevoelig, 20 s time-out):
  bij herhaling stabieler maken.

## 1. Audio van de telefoon naar de Mac (gebouwd in 0.1.42, nog niet op echte toestellen gehoord)

- Android: `SoundShareService` en `SoundShareActivity` (AudioPlaybackCapture, toestemming voor opnemen en de vraag van het
  systeem, elke keer), een rij op de apparaatpagina van een Mac die `audio.play` meldt.
- Mac: `PhoneSound` met een buffer (`SoundRing`, zelftest `TANDEM_DEBUG_SOUNDRING=1`), een audio-engine, een schakelaar en een
  keuze voor vertraging in Instellingen. Zie docs/MUSIC_AND_SOUND.md.
- Te controleren op de Xperia en de Mac: hoort het geluid er, hoe groot is de vertraging, welke apps blijven stil omdat ze
  opname niet toestaan, wat doet de stilte bij een netwerkhapering.
- Nog niet: Opus of een andere codec voor een slecht netwerk, geluid van de telefoon naar Windows.

## 2. Windows (experimentele Tauri-app)

Volgorde, van goedkoop naar duur:
1. Venster met het telefoonscherm en de telefooncamera: gebouwd in 0.1.43 (`windows/src-tauri/src/live.rs`, `windows/ui/live.html`,
   decoderen met WebCodecs). De decoder is in een browser geprobeerd met een opname, het geheel nog niet op een echte pc.
   Nog niet: een aanbod vanaf de telefoon (`MediaOffered`), bedienen van de telefoon vanuit Windows, de pc melden als kijker.
2. De pc bedienen vanuit Android: Windows.Graphics.Capture, Media Foundation H.264, invoer met SendInput.
3. Klembordgeschiedenis, de nieuwe bestandenpagina (zoeken, selecteren), klik op een melding opent de app.
4. Invoegen vanaf je telefoon (ontvangen en plakken), geluid van de telefoon, hotspot en Bluetooth
   voor zover Windows dat toelaat.
Alles in CI testen (de app laat zich alleen daar bouwen) en op de laptop van Mark laten proberen.

## 3. Linux

- Gedaan in 0.1.46 (experimenteel, nog niet op een echt Linux-bureaublad gezien): de app van Windows gebouwd op Ubuntu als
  .deb en AppImage (`scripts/build-linux-app.sh`, `.github/workflows/linux.yml`, de release `linux-preview` en een job `linux-app`
  in de releaseworkflow). Het systeem meldt zich als Linux, de batterij komt uit `/sys/class/power_supply`, updates van
  Windows staan uit.
- Nog niet: mediabediening via MPRIS (de muziek van de telefoon in de mediabediening van het bureaublad), luisteren naar het
  klembord zonder te pollen, de invoer op Wayland (enigo werkt vooral op X11), het venster met het telefoonscherm testen in
  WebKitGTK (WebCodecs is daar niet vanzelfsprekend), een eigen updatepad, een pakket voor Flatpak of de AUR.
- Daarna: je Linux-computer bedienen vanuit Android (schermopname via PipeWire en een encoder).

## 4. Muis en toetsenbord over computers

- Onderzoek en ontwerp staan in docs/INPUT_SHARING.md (Input Leap, lan-mouse, Universal Control). In de kern staat het bericht
  `PointerShareMsg` en de rekenlogica (`pointer_share.rs`) met tests. Nog niet: het zien van invoer en het verbergen van de
  aanwijzer per systeem (Mac `CGEventTap`, Windows hooks, Linux libei), de instellingen en het terughalen met een toets.

## 5. Uiterlijk

- Icoontjes in de rondjes: de verhouding op Android en Mac nalopen (icoon onder de helft van de cirkel).
- Meer Material 3 Expressive: golvende voortgang bij overdrachten, de laadindicator, expressieve vormen.
- Klembordgeschiedenis-scherm op Android.

## 7. Kwaliteit en documentatie

- Remote desktop: adaptieve kwaliteit volledig (nu alleen via bitrate-meldingen), HEVC, meerdere
  kijkers, statistieken testen.
- docs/SCREEN.md bijwerken (cursor in het beeld, capnamen `screen.host`, `screen.view`, `camera.host`,
  `camera.view`) en docs/PROTOCOL.md (`Status.muted`, `MediaOffer`).
- Mesh-tests die onder CPU-load soms falen (`sound_travels_as_datagrams_and_arrives_in_order`,
  `status_reaches_the_other_device`): eerst rustig opnieuw draaien, dan eventueel stabieler maken.

## Werkafspraken voor deze fase

- Hooguit twee agents tegelijk, laat ze vaak committen, en vraag ze Mac-testkopieen met `open -n -g -j`
  te starten en af te sluiten (anders vullen ze het Dock van de gebruiker).
- Na elke afgeronde wijziging: changelog, tests, CI groen, tag, release controleren.
