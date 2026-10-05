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

## 1. Audio van de telefoon naar de Mac

- Android: opnemen van het geluid van andere apps met AudioPlaybackCapture, via dezelfde
  MediaProjection-toestemming als LiveShare (let op: apps kunnen opname uitzetten).
- Mac: ontvanger met jitter-buffer en CoreAudio-uitvoer, instelling voor vertraging zoals op Android
  (AudioDelay), schakelaar per apparaat, duidelijke indicator.
- Kwaliteit nu: onbewerkte PCM 16 bit stereo op de bronsnelheid, geen codec. Overweeg Opus alleen als
  een slecht netwerk dat nodig maakt.

## 2. Windows (experimentele Tauri-app)

Volgorde, van goedkoop naar duur:
1. Venster met het telefoonscherm en de telefooncamera (decoderen in de webview met WebCodecs).
2. De pc bedienen vanuit Android: Windows.Graphics.Capture, Media Foundation H.264, invoer met SendInput.
3. Klembordgeschiedenis, de nieuwe bestandenpagina (zoeken, selecteren), klik op een melding opent de app.
4. Invoegen vanaf je telefoon (ontvangen en plakken), geluid van de telefoon, hotspot en Bluetooth
   voor zover Windows dat toelaat.
Alles in CI testen (de app laat zich alleen daar bouwen) en op de laptop van Mark laten proberen.

## 3. Linux

- Eerst een echte app (nu alleen tandemd in de terminal), daarna schermopname via PipeWire en
  VAAPI of x264 voor de host. De kern is gedeeld; de rest is per besturingssysteem.

## 4. Muis en toetsenbord over computers

- Zoals Universal Control: de aanwijzer loopt over de rand naar een andere computer. Mac en Windows eerst.
  Een eerdere agent begon eraan en is nooit hervat (branch `worktree-agent-ad011acae226a7aa7`).

## 5. Uiterlijk

- Icoontjes in de rondjes: de verhouding op Android en Mac nalopen (icoon onder de helft van de cirkel).
- Meer Material 3 Expressive: golvende voortgang bij overdrachten, de laadindicator, expressieve vormen.
- Klembordgeschiedenis-scherm op Android.

## 6. Nog te beslissen of te onderzoeken

- Delen met iedereen (ook mensen buiten je eigen circle): ontwerp nog niet goedgekeurd.
- Overdracht van een link of tabblad tussen apparaten (handoff): uitgelegd, niet goedgekeurd.
- Vorssaint (Dynamic Island op de Mac): de telefoonmuziek staat al in Now Playing van macOS. Meer
  kan alleen met hun plugin-API; documentatie of naam van die API nodig.
- Telefoon als echte webcam of microfoon: kan niet zonder Xcode en een Apple Developer ID (camera-extensie
  en audiodriver). Het venster met de telefooncamera is het alternatief dat nu bestaat.

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
