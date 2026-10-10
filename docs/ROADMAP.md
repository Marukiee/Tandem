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
