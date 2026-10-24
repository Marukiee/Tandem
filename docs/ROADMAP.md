# Volgende fase

Alles wat nog niet af is, op volgorde van aanpak. Begin bovenaan. Een punt is pas af als het op echte
toestellen is gezien, niet alleen gecompileerd. Zie CLAUDE.md voor de werkwijze en de agentregels.

## Open lijst voor een grote ronde (bijgewerkt 2026-10-09, na versie 0.1.73)

Een regel per punt wat nog open staat, met waar het uitgewerkt is. Elk nieuw verzoek van Mark komt hier bij en in zijn sectie; een punt
gaat eruit zodra het op echte toestellen is gezien. Wat per systeem werkt staat in docs/PARITY.md.

Gedaan en gereleased, nergens op een echt toestel gezien tenzij anders gezegd: Quick Share (0.1.57 tot 0.1.60), koppelen met een
typbare code en uitnodigen vanuit een cirkel (0.1.61, de emulator zag het wel), vegen om te pinnen (emulator), de gedeelde muis
komt terug (0.1.62), deelstatus met stop-icoon, scrollen, Mac-uiterlijk en Wat is er nieuw bij Updates (0.1.64), een SSH-terminal op
Mac, Windows en Linux (0.1.65), de telefoon bedienen vanuit het Windows/Linux-venster en Invoegen vanaf telefoon (0.1.66), geluid van de
telefoon, eigen mappen aanbieden en de mediabediening van het bureaublad op Linux via MPRIS (0.1.67), Windows en Linux (X11) die hun scherm
tonen en bediend worden vanaf Mac, telefoon, Windows en Linux (0.1.69, gebouwd en op de Mac en in CI getest, niet op een echte pc), een terminal
op Android en dropzones op de Mac (0.1.70), bestanden slepen over de rand beide kanten op met landing in het voorste venster of op het
bureaublad (0.1.71, nergens op twee echte computers gezien).

Open, wacht op toegang tot de Linux-pc (Mark regelt dat na de weekreset, SSH met een sleutel van de Mac):
- Alles wat hierboven voor Linux staat nalopen op het echte bureaublad (venster, decoder, instellingen, terminal, MPRIS, geluid).
- De gedeelde muis met Linux als hoofd (gebouwd na 0.1.73 met de InputCapture-portal, nog nooit gezien: of GNOME de vraag toont, of de
  barrieres bij de randen werken, of de snelheid klopt) en voor X11 (XInput2 met grabs, nog niet gebouwd).
- Scherm van de Linux-pc delen (PipeWire en een encoder) en Linux als bediend scherm; een eigen updatepad; Flatpak of AUR.

Open, wacht op een echte Windows-pc: alles van sectie 2 voor Windows (scherm delen vanaf de pc, Windows Graphics Capture en Media
Foundation, hotspot) en alles nalopen.

Open, kan zonder dat die toegang er is, nog niet gedaan:
- Gasten en familie (gevraagd 2026-10-09): familieleden met de app zonder dat hun klembord bij je binnenkomt. Per apparaat kan dat met de
  schakelaar Klembord (die werkt twee kanten op in de kern: wat jij kopieert gaat er niet heen en wat zij kopieren wordt aan jouw kant
  weggegooid, `session.rs` bij `Msg::Clipboard`). Gebouwd in 0.1.75: een instelling "Nieuwe apparaten beginnen als gast" (kern
  `Settings::guests_by_default`, op Mac, Android, Windows en Linux) die klembord en meldingen uit zet en bestanden eerst laat vragen voor
  apparaten die daarna binnenkomen; wat er al is blijft zoals het was. Nog niet: de keuze "Mijn eigen apparaat" of "Een gast" op het moment
  van koppelen, scherm en bediening voor een gast standaard op "nooit", en de instelling die op al je eigen apparaten tegelijk geldt (nu
  zet je hem op elk apparaat apart). Tweede weg: een eigen cirkel per persoon; Quick Share werkt al tussen cirkels, zonder dat er iets
  automatisch meeloopt.
- Bestanden slepen over de rand: loslaten op de plek van de aanwijzer met een systeemsleep op de ontvanger, en Windows of Linux als hoofd met
  een dropzone (sectie 4); de rest vraagt twee echte computers om uit te proberen. In 0.1.77 stopt de sleep op de Mac bij de rand en toont
  de pill wat er meegaat en dat het is neergezet (gevraagd 2026-10-10: het plaatje bleef op de rand hangen). Een echte systeemsleep tussen twee
  besturingssystemen bestaat niet; onder Wayland is bovendien niet te zien wat er gesleept wordt, dus van Linux als hoofd werkt alleen de randzone.
- Slepen naar een Linux-computer met een plaatje onder de muis (gevraagd 2026-10-10): onder Wayland kan een app geen venster op de plek van de
  muis zetten en geen echte sleep starten, dus er is geen icoon onder de aanwijzer en geen loslaten op een map. Wat er is: de Mac toont wat er
  meegaat (pill) en het bestand komt in Downloads met een melding "Tonen". Een stukje aanwijzer blijft op GNOME zichtbaar aan de rand zolang de muis
  naar een andere computer is (de aanwijzer van het bureaublad is niet te verbergen). Mogelijk met een XWayland-hulpvenster of een GNOME-extensie.
- De vraag om toestemming van de Linux-portals kwam steeds terug (gevraagd 2026-10-10): sinds 0.1.77 heeft elke manier van starten (login, menu,
  terminal) zijn eigen token (`portal.rs`, `launch_identity`). Nog niet gezien of het daarmee weg is; InputCapture op GNOME 50 (versie 1)
  blijft het bij elke start vragen, daar is geen token voor.
- De telefoon als scherm voor de gedeelde muis (gevraagd 2026-10-09, in 0.1.73 nog niet gebouwd): de muis van de Mac gaat over de rand naar de telefoon,
  die een eigen aanwijzer als overlay tekent en klikken, slepen en scrollen afspeelt met de toegankelijkheidsservice die er al is voor het bedienen
  vanaf de Mac (`TandemInput` is hetzelfde bericht). Een Bluetooth-muis aan de telefoon als bron voor andere computers is veel moeilijker: Android
  geeft een app buiten zijn eigen venster geen muisbewegingen.
- Linux onder Wayland (de standaard van Fedora): invoer via de RemoteDesktop-portal en beeld via de ScreenCast-portal werken met een onthouden toestemming (gezien
  op een Fedora 44 met GNOME 50, 2026-10-09). Nog niet gezien: een kijker die dat beeld echt krijgt, en dat alles na een herstart van het bureaublad.
- Scherm delen en bedienen vanaf beide kanten tussen Mac en Linux (en Windows) op echte toestellen nalopen; de code voor beide kanten is er, nooit gezien.
- Android (sectie 5b): de deelstatus ook in de tegels en de melding, Control this Mac nog soepeler (eerst meten op het toestel),
  bellen via de Mac (alleen de melding met opnemen en ophangen; het gespreksgeluid kan niet).
- Mac (sectie 5): het menu Diensten laat alleen zien wat aanstaat kan niet zonder de Info.plist te veranderen; keuze voor Mark.
- Alles op alle systemen (sectie 8): Windows en Linux als host voor het eigen scherm en voor Invoegen vanaf telefoon als bron voor een
  andere computer, geluid van een computer naar een andere computer, de hotspot vanaf de computer.
- Tweede scherm (gevraagd 2026-10-11, gebouwd in 0.1.82, nergens op echte toestellen gezien): vanaf de computer aanvragen met "Scherm uitbreiden naar
  dit apparaat" (Mac en Linux; de telefoon opent het meteen of via een melding), en aanraken op een scherm dat Linux voor de telefoon maakt
  (een sessie met scherm en invoer via de portal, `StreamPlay` in `host.rs`). Te zien op de laptop: of GNOME de gecombineerde vraag toont, of
  een aanraking op de goede plek klikt, en of het scherm alleen-kijken wordt als de desktop de combinatie weigert. Windows als host kan nog niet (virtueel
  beeldschermstuurprogramma).
- Bluetooth tussen computers (gevraagd 2026-10-11, gebouwd in 0.1.82, nooit op twee echte computers gezien): Mac en Linux wachten en zoeken naar elkaar
  (docs/BLUETOOTH.md, "Between computers"). Windows heeft er nog geen code voor (WinRT: `GattServiceProvider` om te wachten, een watcher om te zoeken).
  Ook open: de status van een computer die alleen over Bluetooth bereikbaar is staat nu blauw op Android, Linux en Windows (Mac had het al).
- Gevraagd 2026-10-12 tot 2026-10-14, gebouwd in 0.1.83, nergens op echte toestellen gezien: de melding "kijkt mee" op de Mac wordt niet meer afgesneden
  (de schaduw had geen ruimte in het paneel); op Linux en Windows wordt "Gebruik als tweede scherm" en "Scherm uitbreiden naar dit apparaat" een
  Stop-knop zolang het loopt; de balk van een tweede scherm op Android verdwijnt vanzelf en komt terug met een veeg van de bovenrand; een
  schuifbalk voor de snelheid van de aanwijzer in het trackpadscherm van Android; de aanwijzer van een Windows-pc staat in het beeld dat een
  telefoon ziet (GDI laat hem weg, `draw_pointer` in `winsys/src/imp.rs` tekent hem erin); de Mac heeft een knop "Telefoon als uitvoer" bovenaan de
  pagina van een telefoon; het vinkje in de filtermenu's van Android staat achter de tekst; de hotspot-teksten op Android zeggen computer in plaats
  van Mac; de computer bedienen vanaf de telefoon onder Wayland gaat nu door dezelfde sessie als het beeld (een vraag, klikken op de aanraakplek).
- Melding bij een slechte verbinding (gevraagd 2026-10-12, gebouwd in 0.1.84, nergens gezien): de kern (`judge` in `live/viewer.rs`) zegt
  `MediaQuality` als drie rapporten van een seconde achter elkaar een tiende van de frames kwijt zijn of een pakket 400 ms of meer duurt, en weer
  goed na vijf goede. Mac en Android tonen een melding met de naam, het venster van Linux en Windows een pil over het beeld, en een tweede scherm
  op Linux een bericht in het hoofdvenster. Alleen voor wat deze computer bekijkt; de computer die het beeld maakt krijgt niets te zien.
- Windows: Bluetooth tussen computers, een scherm voor een ander apparaat maken (stuurprogramma voor een virtueel beeldscherm), en alles nalopen op
  een echte Windows-pc (nooit gedaan, zie docs/PARITY.md).
- Tandem in het deelmenu van de Mac (gevraagd 2026-10-20, gebouwd in 0.1.85, niet gezien in Finder): een deelextensie (`macos/ShareExtension`, gebouwd met
  `swiftc` in `scripts/build-macos.sh`, ondertekend met eigen rechten) geeft de bestanden door aan de app met een `tandem://send`-adres, en de app
  vraagt welk apparaat. Het systeem toont hem als deeldienst (`pluginkit`); de gebruiker moet hem mogelijk aanzetten onder Systeeminstellingen,
  Extensies, Delen. Nog niet: tekst en links delen, een regel per apparaat ("Stuur naar Maruks telefoon" kan in het deelmenu niet, daar staat een regel
  per extensie), en het klembord sturen vanuit het Controlecentrum.
- Knoppen in het Controlecentrum van de Mac (gevraagd 2026-10-24, gebouwd in 0.1.88, nog niet in het Controlecentrum gezien): een WidgetKit-extensie
  (`macos/ControlExtension`, een minimaal Xcode-project dat `scripts/build-macos.sh` met `xcodebuild` bouwt, want de metadata van de intents maakt alleen
  Xcode) met Quick Share, Scherm uitbreiden, Gedeelde muis en Telefoon zoeken. De extensie leest wat aan staat uit `control-state.json` (geschreven door
  `ControlBridge`) en doet een druk door `tandem://control?action=...` te openen zonder de app naar voren te halen. Het systeem toont hem als extensie
  (`pluginkit`); toevoegen gaat via Controlecentrum, Bewerken. Nog niet: Klembord sturen en Telefoon als uitvoer als knop, en een keuze van het apparaat
  voor Scherm uitbreiden (nu het laatst gebruikte, anders het eerste dat het kan).
- De kant vinden met geluid (gevraagd 2026-10-24, gebouwd in 0.1.90 en 0.1.91, nergens op echte toestellen gehoord): de computer die plaatst speelt
  links een sweep omhoog en rechts tegelijk een omlaag (`side.rs` in de kern, zoals Sonos bij het afstemmen), het andere apparaat neemt op en vergelijkt
  de opname met beide (een matched filter, geen getraind model: voor een bekend geluid is dat het beste). Belletje en piep zijn eruit: met galm per
  speaker waren die een stuk slechter. Eerst zegt de luisteraar dat de microfoon aan staat, dan pas klinkt het, zacht eerst en harder als het niet goed
  gehoord werd. Zo duurt het ongeveer 2 seconden. De kant gaat naar de gedeelde muis en naar een tweede scherm (Mac, en nu ook Linux: GNOME zet een
  gemaakt scherm rechts, Tandem zet het met `ApplyMonitorsConfig` links als het apparaat links staat, alleen voor die sessie). Bij het eerste tweede
  scherm vraagt de Mac waar het apparaat staat (zoeken, links of rechts, met Onthouden). Een telefoon die bewogen wordt (bewegingssensor, alleen met
  Tandem open) laat de kant opnieuw bepalen. Nog niet: de vraag bij het eerste keer op Linux en Windows, en de Mac zelf heeft geen bewegingssensor
  die een app mag lezen.
- Wat werkt per apparaat (gevraagd 2026-10-24, gebouwd in 0.1.92, nog niet op echte toestellen gezien): elk apparaat meldt in zijn status welke
  functies klaarstaan en wat er anders mist (`Feature` in de kern: id, klaar, naam, waarom, pagina), in zijn eigen taal. Mac: bedienen en gedeelde muis
  (Toegankelijkheid), scherm (Schermopname, of herstart nodig), mediaknoppen (Automatisering voor Spotify en Muziek, gevraagd zonder vraag aan de persoon
  met `AEDeterminePermissionToAutomateTarget`), meldingen, microfoon, Bluetooth. Android: bedienen (toegankelijkheidsdienst), meldingen en muziek
  (meldingstoegang), gesprekken, bestanden, camera, microfoon, achtergrond (batterij), Bluetooth, eigen meldingen. Linux en Windows: bedienen, scherm,
  microfoon, Bluetooth (Linux). Een knop "Open op <apparaat>" stuurt `OpenSettings`: de Mac opent de goede pagina van Systeeminstellingen (of stelt de
  vraag van macOS als die nog nooit gesteld is), Android opent de pagina direct met Tandem in beeld en anders via een melding om op te tikken, Linux opent
  GNOME- of KDE-instellingen, Windows `ms-settings:`. Overzicht "Wat werkt" in de instellingen van alle apps, een melding op de apparaatpagina, de
  mediaknoppen op Android grijs met uitleg als de Mac ze niet mag bedienen, en de rij Mac bedienen op Android toont wat er mist met de knop.
  Nog niet: meer knoppen elders grijs maken op basis hiervan (bijvoorbeeld Telefoon bedienen op de Mac), en Windows-meldingen en microfoonprivacy meten.
- Geluid bij scherm bekijken en bedienen (gevraagd 2026-10-24, gebouwd in 0.1.96, nog niet op echte toestellen gehoord): de kijker vraagt het geluid
  op met `SoundRequest` als de instelling op "hierheen" staat, of na een vraag als er een koptelefoon in zit (Bluetooth, de aansluiting of USB; met
  "Altijd hier"). De bron stuurt het zoals een telefoon zijn geluid stuurt (stroom 9) en stopt als de schermsessie eindigt. Mac: de bestaande opname
  van het systeemgeluid (Telefoon als uitvoer). Linux: `parec` van de monitor van de standaarduitvoer, anders GStreamer `pulsesrc`. Windows: loopback
  van cpal. Android: delen met `SoundShareService`, met de vraag van Android erbij (direct met Tandem in beeld, anders via een melding). Elke kijker
  speelt het af, ook als geluid van een telefoon uit staat. Nog niet: de vraag van Android overslaan met de projectie van het scherm dat al gedeeld wordt,
  en een koptelefoon herkennen op Windows.
- Alleen Engels in de release-teksten op GitHub en in de update-melding van de apps (gevraagd 2026-10-24, gedaan in 0.1.95 en de README).
- Linux: Bijwerken en herstarten herstart niet, en het lettertype oogt van boven ingedrukt (gevraagd 2026-10-24, gedaan in 0.1.95: de nieuwe versie
  start pas als de oude weg is, en de app gebruikt Google Sans Flex zoals Android).
- Een bestand over de rand slepen en neerzetten waar de aanwijzer is, ook in een veld op een website (gevraagd 2026-10-24). Gedaan in 0.1.97: na het
  neerzetten gaat op Linux en Windows de map open met het bestand geselecteerd. Gedaan in 0.1.99 voor de Mac als ontvanger (`DragCarrier`): het bestand
  hangt als icoon onder de aanwijzer (een klein paneel precies onder de punt), en de volgende klik, van de eigen muis of doorgegeven door de computer die
  de muis deelt, landt op dat paneel en begint een echte systeemsleep; loslaten zet het neer in elk veld of elke app die bestanden neemt. Escape of 30
  seconden niets: de map zoals voorheen. Nog nergens gezien. Niet mogelijk: Linux als ontvanger onder Wayland (een app kan geen sleep beginnen zonder
  klik op zijn eigen venster en geen venster onder de aanwijzer zetten), daar blijft het de map. Windows als ontvanger kan op dezelfde manier als de Mac
  (een klein venster onder de aanwijzer en `DoDragDrop`), nog niet gebouwd.
- Mac: na een update mocht Tandem niet meer op het lokale netwerk (gezien 2026-10-24 na 0.1.97: "No route to host" naar Linux en telefoon in het
  systeemlogboek, Linux en Mac vonden elkaar niet). Oplossing voor de persoon: Privacy en beveiliging, Lokaal netwerk, Tandem uit en aan. Te doen: het
  herkennen in de app (een `NWBrowser` op `_tandem._udp` die wacht met `kDNSServiceErr_PolicyDenied`) en het zeggen, in Wat werkt en als melding, met een
  knop naar die pagina; en uitzoeken waarom het bij een update wegvalt. Vermoeden: zonder Team ID kent macOS de app aan de UUID's van zijn programma's (de
  hoofdapp en de twee extensies, `com.apple.networkextension.uuidcache.plist`), en die zijn bij elke build nieuw. Mogelijke oplossing: een vaste LC_UUID
  in de build zetten (gevolg: de berichten in het systeemlogboek kunnen verkeerd gelezen worden), of telkens na een update zelf vragen. Gedaan in
  0.2.0: `LocalNetwork` herkent het met een `NWBrowser` en toont een glazen melding met Open Instellingen, en Wat werkt zegt het (functie `network`).
  Nog open: de vaste LC_UUID, als het na de volgende update weer gebeurt.
- Android: een tegel in de snelle instellingen om de muis van een computer te ontvangen aan en uit te zetten, zoals op de Mac (gevraagd 2026-10-24,
  gebouwd in 0.2.0: `PointerTileService`). De telefoon meldt de functie `pointer` (de schakelaar of de toegankelijkheidsdienst), en de Mac zegt bij
  een terugkaatsende aanwijzer wat er uit staat en opent die instelling op de telefoon, hooguit eens per tien minuten.
- Windows en Linux: dezelfde snelle knoppen als het Controlecentrum van de Mac (Quick Share, Scherm uitbreiden, Gedeelde muis, Telefoon zoeken), gevraagd
  2026-10-24. Windows 11 laat apps niets aan de snelle instellingen toevoegen: het menu van het pictogram in het systeemvak en een jumplist. Linux: GNOME
  alleen met een extensie van GNOME Shell (Quick Settings), KDE met een plasmoid; anders het menu van het pictogram. Gedaan in 0.2.1: het menu van het
  pictogram heeft Quick Share, Gedeelde muis (aan en uit, de opstelling blijft), Mijn scherm uitbreiden naar (per apparaat, met een vinkje als het loopt) en
  Telefoon zoeken (per telefoon); het menu wordt opnieuw gemaakt als de apparaten veranderen. Nog niet: een echte tegel in GNOME of KDE.
- Uitbreiden van de Mac naar Linux (gevraagd 2026-10-24, gebouwd in 0.2.1 door een agent met metingen op de laptop over SSH): de Linux-app vroeg 3840x2400
  (GTK meldt 1920x1200 bij schaal 2) terwijl het paneel 2560x1600 is bij 133%; de Mac maakte 3642x2276 en haalde daarbij maar 41 tot 45 beelden per seconde.
  Nu stuurt de kijker zijn echte pixels en zijn logische maat (`logical_width`, `logical_height` in het verzoek), maakt de Mac een hiDPI-scherm van die
  logische maat en stuurt het in de echte pixels (2560x1600 haalt 60 fps). De speler op Linux hield elk beeld 85 tot 90 ms vast (een draad per beeld
  in `avdec_h264`, `h264parse` die op het volgende beeld wacht, een wachtrij die bytes weggooide); nu 6 ms, en het laatste beeld van een beweging 5 ms in
  plaats van 522. In de kern werden beelden die hun sleutelbeeld inhaalden weggegooid (bevriezen aan het begin); die worden nu bewaard. Gemeten maar niet
  op te lossen in code: de wifi van de laptop haperde (energiebesparing aan, pieken tot 128 ms), uit te zetten met NetworkManager `802-11-wireless.powersave 2`.
  Hardware-decoderen op de laptop kan niet zonder RPM Fusion (`mesa-va-drivers-freeworld`); software is 4,6 ms per beeld bij 2560x1600. Nog niet gezien op
  de echte toestellen: of het beeld precies 1:1 staat, en de soepelheid. Gewone kijkvensters op Linux gebruiken nog het JPEG-pad met een maximum van 1280.
  In 0.2.2 voor elke Linux-laptop (gevraagd 2026-10-24, "beste compatibiliteit"): `decoder_probe.rs` probeert bij de start elke H.264-decoder die er is
  (VA-API nieuw en oud, V4L2 voor ARM, avdec, openh264, en de NVIDIA-kaart alleen als niets anders werkt) met 40 zelfgemaakte beelden in het tempo van een
  scherm, kiest de snelste (hardware als die binnen 3 ms zit) en bewaart dat in `~/.cache/tandem/decoder.txt` tot GStreamer verandert. Op de laptop van Mark
  met dezelfde meting: avdec_h264 2,9 ms, openh264dec 37 ms, nvh264dec 101 ms. `TANDEM_DISPLAY_DECODER` gaat nog steeds voor.
- Twee tweede schermen tegelijk vanaf de Mac (gevraagd 2026-10-24: Linux links en Android rechts): gebouwd, `ScreenHost` houdt een lijst van sessies,
  elk met eigen scherm, opname, encoder en bitrate, tot vier extra schermen naast het ene dat het eigen scherm toont; de balk toont ze allemaal met een
  Stop per apparaat (zie SCREEN.md). Bewezen met bouwen en een kerntest (`two_devices_each_have_a_second_screen_at_once_and_stop_on_their_own`). Nog
  te zien op echte toestellen: twee schermen links en rechts op hun plek, de balk met twee rijen, het ene stoppen terwijl het andere doorgaat. De vraag
  "verhuizen" komt nu pas bij het vijfde extra scherm. Nog niet: dezelfde vraag op het apparaat dat "bezet" te horen krijgt (`take_over`).
- De muis bij bedienen van de Linux vanaf de Mac staat niet op dezelfde plek als op de Linux (gezien 2026-10-24). De code rekent in theorie goed (Mutter
  deelt de plek op de stream door de schaal), dus eerst meten: sinds 0.2.3 schrijft de Linux bij het begin van een sessie de maat en plaats van de stream,
  de schermen met hun schaal en welke invoer gebruikt wordt (`screen with input:` en `screen for ...:` in tandem.log). Vragen: verschuift het meer naar
  rechtsonder, of loopt het alleen achter?
- AirDrop op de telefoon (gevraagd 2026-10-24): niet te bouwen in Tandem. Een tweede AirDrop-naam op de Mac kan alleen met een namaak (OpenDrop, AWDL),
  en AirDrop op Android zit sinds 2026 in Google's Quick Share zelf, per fabrikant ingebouwd in de wifi-firmware (Pixel 10, Samsung S24 en S25, OnePlus,
  Oppo, Honor, Xiaomi), zonder toegang voor andere apps. Shizuku komt niet bij de firmware; root alleen met een losse wifistick. Doorsturen via de Mac
  wil Mark niet.
- Het Apple-ecosysteem op alle apparaten: wat nog mist (gevraagd 2026-10-24, het doel van Mark). Op volgorde van wat het meeste geeft:
  1. Handoff: geschrapt door Mark (2026-10-24), de browser doet dit al.
  2. Berichten op de computer: gebouwd (2026-10-24). Een pagina Berichten op Mac, Linux en Windows met de sms-gesprekken van de telefoon, live,
     sturen vanaf de telefoon en een nieuw bericht aan een contact; per computer aan of uit op de telefoon. Groepsgesprekken en MMS sturen nog niet.
  3. Klembord met plaatjes en bestanden, niet alleen tekst. Haalbaar.
  4. Wifi delen: gebouwd (2026-10-24). De telefoon vraagt "De wifi van je MacBook gebruiken?", de computer vraagt of het wachtwoord mag, ook over
     Bluetooth; Wifi delen op de pagina van een apparaat, ook tussen computers. Van telefoon naar computer kan niet (Android geeft het wachtwoord niet).
  5. Foto's van de telefoon vanzelf op de computer, zoals iCloud-foto's maar lokaal (schermafbeeldingen gaan al). Haalbaar.
  6. Goedkeuren met de telefoon: geschrapt door Mark (2026-10-24), ziet het nut niet.
  7. De computer laten piepen en zien waar hij is vanaf de telefoon (Find My voor laptops), met het netwerk waar hij laatst was. Het geluid komt uit
     de eigen systeemgeluiden van elk apparaat en is in de instellingen te kiezen (Mac `/System/Library/Sounds`, Linux het geluidsthema, Windows
     `C:\Windows\Media`, Android de kiezer van het systeem). Gekozen door Mark. Gebouwd (2026-10-24): Speel geluid af op de pagina van elke
     computer met laatst gezien en wifinaam (`Ring`, `Ringing`, cap `ring`, `seen.cbor`), nog niet op echte toestellen geprobeerd.
  8. Focus en Niet storen gelijk op alle apparaten. Gebouwd (2026-10-24, op de Android-emulator gezien, niet op echte toestellen): Android een eigen
     modus "Focus van <apparaat>", Linux GNOME en KDE, Windows alleen lezen, Mac lezen en zetten met de eigen opdracht Tandem Focus (één klik, vraagt iCloud).
  9. Een tablet met pen als tekentablet voor de Mac (druk en kanteling, zoals de Apple Pencil bij Sidecar). Haalbaar met de invoer van een tweede scherm.
  10. Bellen met het geluid op de computer (microfoon en luidspreker van de Mac). Via Bluetooth kan het op de Mac niet (macOS geeft het
      gespreksgeluid niet aan een app). De weg via de telefoon zelf (de systeem-API's voor het afvangen van gespreksgeluid, met de rechten van de
      shell via Shizuku) is niet gebouwd: de agent die het zou bouwen stopte (2026-10-24) omdat een veiligheidscontrole het afvangen van het
      geluid van een gesprek met geleende rechten tegenhield. Wat wel kan zonder die weg: Linux als Bluetooth-headset van de telefoon (PipeWire
      met de rol `hfp_hf`) en Windows via Telefoonkoppeling. Nog uit te zoeken of Tandem dat op Linux kan aanzetten.
  11. AirDrop zelf: niet te bouwen (zie het punt over AirDrop hierboven).
- Slepen over de rand zonder vak (gevraagd 2026-10-24: "dat sleep vak is lelijk", de muis bleef haken aan de rand van Linux). Gedaan in 0.2.8:
  het vak op de Mac is weg; op Linux vangt een onzichtbare strook van de X-server (ook onder Wayland, getest op GNOME 50) wat er tegen de rand
  wordt gesleept zolang de knop ingedrukt is, en leest het bestand al tijdens het slepen; de sleep daar wordt afgebroken, de muis gaat met de
  knop nog ingedrukt terug naar de Mac en daar begint een echte sleep, zodat je loslaat waar je wilt. Windows doet hetzelfde met een venster
  van twee punten breed. Op Linux zelf loslaten in een websiteveld kan niet: GNOME breekt een sleep af die een app zelf begint (getest). De
  map naar voren halen mag een app op GNOME ook niet (getest), daarom na een sleep een melding met "Toon in map" die dat wel mag.
- Sturen met Tandem vanuit de bestandsbeheerder op Linux en Windows, zoals de deelknop op de Mac (gevraagd 2026-10-24). Gebouwd (`sendwith.rs`):
  Bestanden krijgt met `nautilus-python` een eigen regel met de apparaten die nu bereikbaar zijn (anders onder Scripts), Dolphin een servicemenu,
  Nemo acties, Windows een snelkoppeling per apparaat in "Sturen naar". Een schakelaar in Instellingen, Bestanden haalt het weer weg. Thunar niet
  (dat zou de eigen instellingen van Thunar aanpassen). Bestanden laadt een nieuwe extensie pas na `nautilus -q` of opnieuw inloggen.
- Stand 2026-10-24 avond (sessielimiet), open:
  - Berichten en Focus zijn samengevoegd in 0.2.10 (nog niet op echte toestellen gezien).
  - Windows: loslaten op de plek zelf is aangesloten in 0.2.10 (`dragsource.rs` vanuit `drag.rs`), niet op een echte pc gezien. Gaat de
    muis met de knop nog vast terug naar de Mac, dan breekt Tandem de sleep af en gooit het bestand weg.
  - Scrollen op Android (universele muis en het telefoonvenster op de Mac): in 0.2.10 loopt de vinger met de afgevlakte snelheid van het
    trackpad mee en haalt hij in wat achterloopt, in plaats van per stapje te springen. Nog meten op de Xperia.
  - Linux als hoofdcomputer: de muis van Linux gaat niet naar de Mac (gezien 2026-10-24). In de sessie sinds 15:03 heeft GNOME de barrière
    nooit meer gemeld, ook niet voor de muis van de Mac. Sinds 0.2.10 schrijft `inputcapture` de schermen en lijnen van de barrières in het
    logboek; na het bijwerken eerst opnieuw proberen en dan dat logboek lezen. Daarna: slepen vanaf Linux als hoofd met dezelfde
    onzichtbare strook (die moet er dan altijd zijn aan de kant van de buur, de knop van Linux zelf is onder Wayland niet te zien).
- Nieuwe wensen van Mark (2026-10-24 avond):
  - Scherm uitbreiden van Mac naar Linux heeft de verhouding van de Mac, niet schermvullend op Linux. Linux vraagt wel zijn eigen maat
    (2560x1600 pixels, 1920x1200 logisch). Sinds 0.2.11 schrijft de Mac de gevraagde en echte maat in `pointer-share.log`, en haalt hij
    een extra scherm uit een spiegelset (macOS onthoudt een scherm aan zijn serienummer, ook als spiegel).
  - Scherm uitbreiden naar Android ook staand, en het volgt de draaiing van de telefoon.
  - Bij elk uitbreiden en elke keer dat de muis overgaat even controleren aan welke kant een apparaat staat (zonder hoorbare piep, als dat
    kan).
  - De zoekbalk van Bestanden gelijk aan die van Klembord.
  - Elk apparaat moet de geluidsuitvoer van elk ander kunnen zijn, niet alleen een telefoon voor de Mac.
  - Een telefoon die uit stond: de muis zegt één keer dat hij weg is en daarna niets meer (ongeveer 10 minuten). Op de achtergrond blijven
    proberen en de muis laten doorgaan zodra hij er weer is, zonder meldingen.
  - Onderzocht: de Mac wakker maken of gebruiken met de klep dicht (in de tas). Uitkomst: in een tas op batterij lukt wakker maken niet
    betrouwbaar (`womp` staat op batterij uit, na standby slaapt hij diep). Thuis aan de lader met een Apple TV of HomePod als Sleep Proxy
    wel. Klep dicht en wakker blijven kan alleen met beheerdersrechten (`pmset disablesleep`), riskant door warmte in een tas. Mark wil
    geen kaartje over wakker maken (2026-10-24). Klep dicht wakker houden pas als laatste, alleen aan te zetten, met timer en uit bij warmte
    of lage batterij.
  - Onderzocht: NFC zoals NameDrop. Android Beam is weg sinds Android 14, iPhone laat apps geen NFC delen. Kan alleen tussen twee
    Android-telefoons met Tandem of met een NFC-sticker op een laptop. Mark wil allebei niet (2026-10-24), van de lijst.
  - Onderzocht: Android laten doen alsof hij een iPhone is (voor wakker maken, NameDrop, AirDrop). Niet haalbaar: Continuity gebruikt
    sleutels die via iCloud tussen apparaten van hetzelfde account gaan, AirDrop heeft AWDL nodig (een wifi-modus die Android niet
    heeft), en Apple blokkeerde Beeper Mini binnen twee dagen. Tandem doet klembord, bestanden en meldingen al met een eigen protocol.
  - De hover van de knoppen hapert.
- Nog te controleren op echte toestellen: sectie 0.

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
  klembord zonder te pollen, de invoer op Wayland (enigo werkt vooral op X11; sinds 0.1.61 is de schakelaar grijs met uitleg
  onder Wayland, echte invoer vraagt libei en een toestemmingsvenster), een eigen updatepad, een pakket voor Flatpak of de AUR.
- 0.1.61: eigen vensterbalk, ronde knoppen in het livevenster, een eigen H.264-decoder (`video.rs`, OpenH264 en JPEG) omdat
  WebKitGTK waarschijnlijk geen WebCodecs heeft, woorden per systeem. Allemaal alleen op een Mac en in CI gezien.
- **Toegang tot de Linux-computer van de gebruiker (genoemd 2026-10-05, nog niet gedaan):** met SSH vanaf de Mac (de gebruiker zet
  sshd aan en zet zelf een sleutel van de Mac in `authorized_keys`) kan ik de app daar draaien, logs lezen en schermafbeeldingen
  nemen (Wayland: `grim` of de portal, X11: `scrot`). Dan kan alles voor Linux echt nagelopen worden.
- Daarna: je Linux-computer bedienen vanuit Android (schermopname via PipeWire en een encoder).

## 4. Muis en toetsenbord over computers

- Onderzoek en ontwerp staan in docs/INPUT_SHARING.md (Input Leap, lan-mouse, Universal Control). In de kern staat het bericht
  `PointerShareMsg` en de rekenlogica (`pointer_share.rs`) met tests.
- Gedaan, nog niet op echte computers naast elkaar gezien: de Mac als hoofd en bestuurd (0.1.49), Windows bestuurd (0.1.50),
  Windows als hoofd met hooks (0.1.51). Instellingen en een toets om terug te halen staan op Mac en Windows.
- Nog niet: Linux (zien van invoer via libei of evdev, afspelen via libei of XTest), een gedeeld klembord bij de overgang,
  ketens van meer dan twee computers, toetsvertaling voor andere indelingen dan US.
- Gedaan in 0.1.71 (beide kanten): de computer die de aanwijzer van een andere gebruikt krijgt aan de rand waar die zit een dropzone (Mac `EdgeDrop`, Windows en Linux `edge.rs`), en een computer die de aanwijzer deelt krijgt bestanden met de herkomst `Drag` zonder vragen en zet ze neer (Finder-venster of bureaublad, `DragLanding`, `drag.rs`). Nog niet: echt loslaten op de plek van de aanwijzer met een systeemsleep op de ontvanger (op het scherm waar de aanwijzer staat), en Windows of Linux als hoofd met een dropzone.
- Gedaan in 0.1.70 voor de Mac als hoofd: tijdens het slepen van bestanden verschijnt aan de rand waar een buurcomputer staat een dropzone (`EdgeDrop.swift`) met zijn naam; loslaten erop stuurt de bestanden. Niet gedaan: de andere kant op (van de buurcomputer naar de Mac), loslaten op de plek van de aanwijzer op het andere scherm, en Windows of Linux als hoofd. Nooit op twee echte computers gezien.
- **Bestanden slepen over de rand (gevraagd 2026-10-05):** als de muis naar een andere computer is gegaan moet je een bestand
  van het ene scherm naar het andere kunnen slepen, zonder haperen, zoals een Mac dat met Universal Control kan, en tussen alle
  systemen (Mac, Windows, Linux, later Android). Ontwerp: bij het slepen van een bestand (muisknop ingedrukt met een
  bestandsarkering) over de rand meldt de bron aan de ontvanger "slepen begonnen" met de bestandslijst (naam, grootte, soort),
  de ontvanger toont een sleepaanwijzing onder de muis (bestanden zijn dan nog niet gekopieerd), en bij loslaten haalt de
  ontvanger de bestanden op via de bestaande overdracht (pull) en zet ze waar de muis losliet (map in Finder of Verkenner, anders
  de downloadmap). Per systeem: Mac `NSDraggingSession` en `NSPasteboard` (bron lezen met een sleep-monitor, doel met een
  eigen `NSDraggingDestination` of door een echte sleep te starten met `beginDraggingSession` op een onzichtbaar venster onder de
  muis), Windows OLE `IDropSource` en `IDropTarget` met `DoDragDrop`, Linux X11 XDND en Wayland `wl_data_device` (op Wayland
  alleen vanuit een eigen venster). Het lastige is de bron: een andere app begint de sleep, dus de app moet de sleep zien
  zonder hem te onderbreken. Wat al kan helpen: de gedeelde-muis-gebeurtenissen (`PointerShareMsg`) en het ophalen van bestanden
  (`files.rs`). Eerst bouwen voor Mac en Windows, Linux volgt zodra de invoer daar werkt.

## 4c. Een ingebouwde SSH-terminal (gevraagd 2026-10-05)

- Een terminal in de app naar de apparaten die dat kunnen (Mac met Remote Login aan, Linux met sshd, Windows met OpenSSH, de
  Android-telefoon alleen met een SSH-app zoals Termux), zonder dat je een ander programma opent. Ontwerp: de app (Mac
  SwiftUI en de Tauri-app) toont een terminalvenster, de kern doet de SSH-verbinding (crate `russh`, sleutels in de
  sleutelhanger van het systeem, host key pinnen op eerste gebruik zoals de rest van Tandem pint), bij voorkeur door de
  bestaande versleutelde verbinding naar het apparaat te gebruiken als tunnel naar poort 22 zodat het ook buiten het LAN en
  zonder poorten openzetten werkt. Weergave met een bestaande terminalemulator (SwiftTerm op de Mac, xterm.js in de Tauri-app).
- **Grijs als het niet goed staat:** de knop op de apparaatpagina is grijs, met de reden ernaast, zolang het apparaat geen SSH
  aanbiedt (Mac: Remote Login uit; Linux: geen sshd; Windows: geen OpenSSH-server; telefoon: geen SSH-server) of er nog geen
  sleutel is ingesteld. Elk apparaat meldt in zijn `Hello.caps` of en hoe het SSH kan ontvangen (`ssh.host`) en de ander toont
  per geval de stappen om het aan te zetten. Zie ook de regel in de werkafspraken: instellingen zonder rechten worden grijs, niet
  verborgen.

## 4b. Quick Share en AirDrop

- Besloten 2026-10-05: Quick Share-protocol zelf in Tandem, zonder Google Play Services, op Android, Mac, Windows en Linux, alleen
  "Iedereen"-modus, met een schakelaar (standaard uit). Plan en onzekerheden in docs/QUICKSHARE.md. Nog niets gebouwd.
- AirDrop naar iPhones: niet te bouwen in een app van derden (AWDL), ook niet op Android zonder Google. Google doet het alleen in zijn
  eigen Quick Share. Wat overblijft voor iPhones: LocalSend-protocol ondersteunen (iPhone-vriend installeert LocalSend). Een QR-pagina
  is door de gebruiker afgewezen.
- Op de Mac kan een knop "Via AirDrop versturen" het systeemvenster openen (NSSharingService), dat is makkelijk en betrouwbaar.

## 5. Uiterlijk

- Icoontjes in de rondjes: de verhouding op Android en Mac nalopen (icoon onder de helft van de cirkel).
- Meer Material 3 Expressive: golvende voortgang bij overdrachten, de laadindicator, expressieve vormen.
- Klembordgeschiedenis-scherm op Android.
- Mac, rij Quick Share in het menubalkpaneel (gevraagd 2026-10-06): de knop Verstuur bestanden is lelijk door het paars, hij moet
  de vorm en kleur van de andere knoppen in het paneel krijgen. Het Quick Share-icoon in die rij heeft niet dezelfde
  hover-animatie als de andere iconen (de cirkel die zich vult en het symbool dat wit wordt, zoals bij Invoegen vanaf telefoon
  en Klembordgeschiedenis): zelfde `RoundIconButton` gebruiken. Zie `UI/QuickShareMenu.swift` en `UI/MenuBarPanel.swift`.

## 5b. Android: delen en de Mac bedienen (gevraagd 2026-10-06)

Gedaan in 0.1.64, nog niet op een toestel gezien: deelstatus groen of rood met stop-icoon op de apparaatpagina (`LiveDeviceSection`),
stoppen van de telefoon als het Mac-venster sluit (ook de warme opname), de vraag Altijd toestaan weg, het beeld dat met het
toetsenbord mee omhoog gaat en de ronde knoppen Aanraken en Toetsenbord (`ScreenViewerScreen`), scrollen met twee vingers als
gebundelde veegbewegingen (`RemoteInput.playScroll`), de hover-uitleg onderin het Mac-telefoonvenster weg, de uitleg over de
extra toestemming die zelf opent, de Quick Share-rij op de Mac, Wat is er nieuw bij Updates. Nog open uit deze sectie: de
deelstatus ook in de tegels en de melding, Control this Mac nog soepeler (meten op een toestel), geluid zonder de vraag naar
scherm delen (kan niet, wel uitleggen), bellen via de Mac.

- **Geluid delen zonder scherm delen:** het systeem kan geluid van andere apps alleen opvangen met `MediaProjection`
  (`AudioPlaybackCapture`), en daar hoort altijd de vraag van Android bij ("begin met opnemen of casten", met de keuze voor een
  app of het hele scherm). Dat kan Tandem niet omzeilen. Wat wel kan: bij alleen geluid wordt er geen beeld gecodeerd of
  verstuurd (`SoundShareService` doet dat al apart van `LiveShareService`) en de vraag wordt zo uitgelegd dat het duidelijk is
  dat alleen geluid gaat; de keuze "een app" boven "hele scherm" voorstellen. Nog te controleren op het toestel of de tekst van
  het systeem toch over scherm delen gaat en of we een eigen uitleg voor de vraag kunnen tonen.
- **Deelstatus zichtbaar en stoppen met een tik:** wat de telefoon deelt (scherm, camera, geluid, Control this Mac) krijgt een
  kleur: groen als het gewoon bezig is, rood als het scherm of de camera live te zien is. Het icoontje in de tegel, de
  melding en de pagina verandert dan in een stop-icoon, zodat een tik het beeindigt. Eén plek voor die staat (`LiveShare`
  heeft de toestand al), de tegels in Snelle instellingen en de rijen op de apparaatpagina lezen die.
- **Control this Mac soepeler:** het beeld (`ScreenViewerScreen.kt`, `VideoSurface`) en de bediening (aanraken, slepen,
  scrollen, toetsenbord) lopen nu per gebaar door elk een eigen pad; de aanraking moet zonder merkbare vertraging worden
  doorgegeven (geen herhaalde allocaties, gebaren gebundeld per frame, de pointer-datagrammen direct) en het scherm moet
  zonder haperen meebewegen.
- **Knoppen Toetsenbord en Muis:** de ronde hoeken horen dynamisch mee te veranderen met aan of uit, zoals bij de knoppen van
  de trackpad (de ene wordt een cirkel/pil naast de andere, de aangezette knop vult zich). Zelfde component als de trackpad-rij
  hergebruiken.
- **Toetsenbord open in staande stand:** het beeld van de Mac blijft nu in het midden van het scherm staan terwijl het
  toetsenbord de onderste helft bedekt. Het beeld moet met het toetsenbord omhoog schuiven (en kleiner worden als dat past),
  zodat het deel waar je op typt in beeld blijft (`WindowInsets.ime`, het beeld in een kolom die de inzet volgt met een
  veer).

- **Uitleg over de extra toestemming (gevraagd 2026-10-06):** het is onduidelijk dat je voor het bedienen van de telefoon vanaf de
  Mac, terwijl het scherm wordt gespiegeld, nog een toestemming moet geven (Toegankelijkheid voor Tandem op de telefoon, en het
  scherm delen zelf). Toon dat als stappen met een vinkje per stap, op de Mac in het telefoonvenster (de kaart Deze telefoon
  bedienen) en op de telefoon in de melding van het delen, zodat de volgorde en het waarom duidelijk zijn. Elke stap grijs tot hij
  gedaan is.
- **De vraag "keuze onthouden" weg (gevraagd 2026-10-06):** Tandem vraagt zelf of een keuze onthouden moet worden bij het delen
  van het scherm, terwijl Android toch elke keer zijn eigen bevestiging vraagt. Onze eigen vraag is dan overbodig: weg, en het
  beleid (vragen, altijd, nooit) blijft een instelling.
- **Hover bij de knoppen onderin het telefoonvenster op de Mac (gevraagd 2026-10-06):** de uitleg die opkomt bij de knoppen onderin
  (`Live/LiveView.swift`) is lelijk en moet weg; een gewone systeem-tooltip met de naam is genoeg, geen eigen animatie.
- **Scrollen met twee vingers op een telefoon die zijn scherm deelt (gevraagd 2026-10-06):** het scrollen vanaf de Mac komt niet aan.
  Zonder root of Shizuku kan een app op Android alleen gebaren afspelen met de toegankelijkheidsservice (`dispatchGesture`), geen
  echte muis of toetsenbord (`InputManager.injectInputEvent` is voor het systeem). Plan: de scrollgebeurtenis van de Mac
  (trackpad met twee vingers, ook de uitloop) wordt een korte veeg op de telefoon, in stukken van ongeveer 16 ms zodat hij
  vloeiend loopt, met de richting omgekeerd of niet volgens de instelling "natuurlijk scrollen". Een echt toetsenbord en echte muis
  is alleen mogelijk met Shizuku (de optionele modus uit het klembordontwerp) of door de telefoon via Bluetooth als HID te laten
  koppelen aan de Mac (dat is de andere kant op: de Mac zou dan als muis voor de telefoon moeten dienen, en macOS kan dat niet
  als app).

- **Venster op de Mac sluiten stopt het delen op de telefoon (gevraagd 2026-10-06):** als het Android-scherm op de Mac wordt
  weggeklikt moet de telefoon ook stoppen met delen (de opname, de melding en de tegel). Nu stopt alleen het venster. De Mac stuurt
  bij het sluiten `media_stop` en Android moet daarop `ScreenPipeline` en `LiveShareService` echt beeindigen (ook bij een
  verbinding die wegvalt); het pictogram in de statusbalk verdwijnt dan.
- **Bellen via de Mac (gevraagd 2026-10-06, de vraag was: kan dat?):** een gesprek op de telefoon met de microfoon en de luidsprekers van
  de Mac, zoals de Continuity-integratie tussen iPhone en Mac, en een melding op de Mac als je bezig bent met bellen om het gesprek
  naar deze Mac te verplaatsen. Eerlijk antwoord: het geluid van een gewoon gesprek is voor een app op Android niet te pakken. Het
  opnemen van gespreksgeluid (`VOICE_CALL`) en het afspelen van geluid in het gesprek is voor niet-systeemapps geblokkeerd, en
  `AudioPlaybackCapture` slaat telefonie over. macOS kan zich ook niet als headset (Bluetooth HFP) aan een Android-telefoon
  aanbieden, dat kan alleen met een iPhone. Windows kan dat wel (Phone Link doet het via HFP), dus voor de pc is er een weg. Wat
  Tandem op de Mac wel kan, zonder dat geluid: de melding met naam en nummer, opnemen, ophangen, dempen en luidspreker aanzetten
  (`TelecomManager`, `ANSWER_PHONE_CALLS`) vanaf de Mac, en een venster als er gebeld wordt met die knoppen. Het verplaatsen van het
  geluid zelf werkt dan alleen met een andere weg: bellen via een VoIP-app (Wi-Fi bellen via Tandem, SIP) of de telefoon als
  Bluetooth-HID/HFP-apparaat, en die wegen zijn per stuk nog niet onderzocht.

- **Wat is er nieuw bij Updates en back-up (gevraagd 2026-10-06):** op Android staat Wat is er nieuw (`ChangelogScreen`) nu onder Over;
  het hoort in de categorie Updates en back-up van Instellingen (`settings_cat_updates`), bij de updatecontrole. Over houdt alleen
  versie en licenties. Op de Mac staat het blok Wat is er nieuw (`SettingsView.swift`) bij Over: dezelfde verhuizing naar de
  sectie Updates. Het sleutelwoordenlijstje in `strings_settings.xml` meeverhuizen.
- **Alleen tonen wat aanstaat in het menu Diensten (gevraagd 2026-10-06):** Verstuur met Quick Share, Verstuur met Tandem en
  Voeg in vanaf telefoon horen niet in Diensten te staan als die functie uit staat (Quick Share uit, klembord uit, geen
  telefoon die het kan). De regels staan vast in `Info.plist` (`NSServices`); een regel kan alleen verborgen worden door hem uit
  het plist te halen of door de dienst bij het kiezen te weigeren. Plan: Info.plist krijgt alleen de regels van functies die
  aanstaan, geschreven bij het starten en bij het wisselen van de schakelaar (en `NSUpdateDynamicServices()` aanroepen), of
  `validRequestor` laten antwoorden met nil zodat macOS de regel grijst of verbergt. Uitzoeken welke van de twee werkt zonder
  herstart van de app. Bevinding 2026-10-07: macOS leest de Diensten uit de `Info.plist` van de app en die staat in de
  ondertekende bundel, dus een regel verbergen op het moment dat een schakelaar uitgaat kan niet zonder de app opnieuw te
  ondertekenen of de voorkeuren van de gebruiker voor het menu (`pbs`) te veranderen, en dat laatste is aan de gebruiker. Nu
  zegt een regel die uit staat dat de functie uit staat (een melding), en dat blijft zo. Keuze voor Mark: de regels Quick Share en
  Invoegen vanaf telefoon uit het plist halen (dan staan ze er nooit) of laten staan met de melding.

## 8. Alles op alle systemen (gevraagd 2026-10-06)

Alle functies die voor de Mac zijn gebouwd moeten ook werken op Windows en Linux, en tussen alle systemen onderling: van Linux naar
Windows, van Windows naar de Mac, en andersom, niet alleen telefoon naar computer. Aanpak: docs/PARITY.md heeft de tabel (geschreven 2026-10-07, bijhouden na elke versie), met per
functie (bestanden, klembord, meldingen, muziek en geluid, bestanden bekijken, Quick Share, gedeelde muis en toetsenbord,
slepen van bestanden over de rand, scherm delen en bedienen, telefoonscherm en camera, Invoegen vanaf telefoon, ssh-terminal,
bellen) en per paar systemen of het werkt, grijs is of ontbreekt, en dan afwerken van boven naar onder. Vragen aan Mark als
iets niet duidelijk is: welke volgorde (eerst Windows of eerst Linux), en welke functies van de Mac horen er niet bij omdat ze
aan macOS zelf vastzitten (Diensten-menu, Controlecentrum, AirDrop-knop).

## 7. Kwaliteit en documentatie

- Remote desktop: adaptieve kwaliteit volledig (nu alleen via bitrate-meldingen), HEVC, meerdere
  kijkers, statistieken testen.
- docs/SCREEN.md bijwerken (cursor in het beeld, capnamen `screen.host`, `screen.view`, `camera.host`,
  `camera.view`) en docs/PROTOCOL.md (`Status.muted`, `MediaOffer`).
- `what_the_policy_says_no_to_is_refused` (tests/files.rs) viel eenmaal om in CI op Ubuntu met "connection lost" en slaagde bij herhalen:
  uitzoeken of de verbinding te vroeg sluit nadat het beleid iets weigert, of dat de test te snel leest.
- Mesh-tests die onder CPU-load soms falen (`sound_travels_as_datagrams_and_arrives_in_order`,
  `status_reaches_the_other_device`): eerst rustig opnieuw draaien, dan eventueel stabieler maken.

## Werkafspraken voor deze fase

- Hooguit twee agents tegelijk, laat ze vaak committen, en vraag ze Mac-testkopieen met `open -n -g -j`
  te starten en af te sluiten (anders vullen ze het Dock van de gebruiker).
- Na elke afgeronde wijziging: changelog, tests, CI groen, tag, release controleren.
