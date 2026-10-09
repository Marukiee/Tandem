# Muis en toetsenbord over computers heen

Eén muis en één toetsenbord voor meer computers: de aanwijzer loopt over de rand van het ene scherm het andere in. Dit
document zegt hoe bestaande programma's het doen, wat Tandem daaruit neemt, wat er al staat en wat er nog moet.

## Hoe anderen het doen

- **Input Leap (en Barrier en Synergy voor hem):** een server heeft de echte apparaten, clients spelen de invoer af. De gebruiker
  zet in een raster waar de schermen naast elkaar staan. Als de aanwijzer op de server de rand bereikt, gaat de aandacht naar de
  buur: de server verbergt en blokkeert zijn eigen aanwijzer en stuurt wat de handen doen naar die client. Toetsen en
  toetsenbordindelingen worden per systeem vertaald. Het klembord loopt mee. Dit bestaat al jaren en werkt op Windows, macOS en
  Linux (op Wayland met beperkingen).
- **lan-mouse (Rust):** per systeem een laag om invoer te zien (macOS en Windows met de eigen API's, Wayland met layer-shell of
  libei, X11 alleen om te spelen) en een laag om invoer af te spelen (wlroots virtuele aanwijzer, libei, XTest, CGEvent,
  SendInput). Versleuteld over UDP met DTLS, de apparaten vertrouwen elkaar met een vingerafdruk van het certificaat. De
  gebruiker kiest een "release bind": een toetscombinatie die de aanwijzer weer terughaalt.
- **Universal Control van Apple:** dezelfde gedachte, met de rand als overgang en het klembord en slepen van bestanden erbij.

De gemeenschappelijke vorm: (1) de computer met de apparaten ziet alle invoer, (2) bij de rand van het scherm waar een ander
scherm aan grenst gaat de aanwijzer over, (3) de eigen aanwijzer wordt verborgen en vastgezet en de bewegingen gaan als verschil
(dx, dy) naar de ander, (4) de ander speelt ze af en meldt wanneer de aanwijzer de rand raakt waar hij binnenkwam, (5) er is
altijd een toets om de aanwijzer terug te halen, ook als de verbinding wegvalt.

## Wat Tandem daaruit neemt

- Het verkeer loopt over de bestaande beveiligde verbinding tussen de apparaten van de circle, dus geen eigen versleuteling.
- De invoer zelf is er al: `InputMsg` (`Pointer`, `Scroll`, `Button`, `Key`, `Text`) wordt nu door een Mac en een Windows-pc
  afgespeeld voor de telefoon. Dezelfde berichten dienen voor een andere computer.
- Nieuw is alleen de afspraak over de overgang: `PointerShareMsg` (`Enter`, `Leave`, `Release`) in `crates/tandem-core/src/pointer_share.rs`,
  met de logica die uitrekent waar de aanwijzer op het andere scherm uitkomt (`enter_at`, ook bij schermen van een andere grootte)
  en wanneer hij terugkomt (`Controlled`). Dat is zuivere logica met tests; de apps doen het deel dat bij het systeem hoort.

## Wat er staat (0.1.49, in de kern)

- `Msg::PointerShare` in het protocol, `Event::PointerShare`, in de FFI als `TandemEvent::PointerShare` en
  `send_pointer_share`. Een apparaat dat het bericht niet kent slaat het over.
- De rekenlogica en de tests voor overgang en terugkeer.

## Wat er staat in 0.1.49 (Mac naar Mac, nog niet op twee echte computers gezien)

- `macos/Sources/Tandem/Platform/PointerShare.swift`: de Mac als hoofdcomputer (een `CGEventTap` die de muis en de toetsen ziet, de
  aanwijzer verbergen en loskoppelen, de bewegingen als `InputMsg` sturen, Control Option Command met Escape om terug te halen)
  en als bestuurde computer (de aanwijzer komt binnen bij de tegenoverliggende rand, de bewegingen gaan onversneld in, en bij de
  rand waar hij binnenkwam gaat hij terug). Instellingen: per apparaat links, rechts, erboven of eronder, en een schakelaar om
  deze Mac te laten gebruiken (standaard uit).

- Windows als bestuurde computer (0.1.50): `windows/src-tauri/src/input.rs` laat de aanwijzer binnenkomen aan de tegenoverliggende
  kant (`pointer_share::enter_at`), speelt de bewegingen onversneld af, en meldt `Leave` bij de rand waar hij binnenkwam. Het gebruikt
  de bestaande schakelaar voor het afspelen van invoer als toestemming.

- Windows als hoofdcomputer (0.1.51): `windows/src-tauri/winsys` heeft de hooks (`WH_MOUSE_LL` en `WH_KEYBOARD_LL`, op een eigen thread met
  berichtenlus). Zolang de aanwijzer hier is, kijkt `windows/src-tauri/src/capture.rs` of hij de rand raakt van alle schermen samen
  (het virtuele bureaublad, dus een tweede scherm aan de overkant is geen reden om weg te gaan). Dan gaat `Enter` naar de buur, wordt de
  aanwijzer in het midden van het scherm vastgezet, worden de bewegingen als verschil gelezen en als `InputMsg` gestuurd, en worden
  de gebeurtenissen opgeslokt. `Leave` van de buur of Ctrl, Alt en Shift met Escape brengt hem terug (`Release` gaat dan naar de buur).
  De toetsen gaan als Mac-codes over (de tabel van `input.rs` omgekeerd), met Ctrl als Command, Alt als Option en de Windows-toets als
  Control, zodat Ctrl+C hier kopieert daar. Instelling: Instellingen, Gedeelde muis en toetsenbord, kies de computer en de kant.
  Nog niet: een tweede computer als buur van de buur (ketens), en op Linux zien van invoer.

- Mac: een tabblad in de Instellingen (0.1.55, `macos/Sources/Tandem/UI/PointerSettings.swift`; in 0.1.53 stond het als pagina in de zijbalk) met uitleg, de toestemming die deze Mac nodig heeft
  (Toegankelijkheid), de plek van elke computer, en per computer of die deze Mac mag gebruiken (`PointerShare.allowed`; de oude ene
  schakelaar blijft gelden tot de pagina is geopend). Een telefoon heeft geen rand om over te gaan: zijn scherm tonen in een venster en de
  muis erin bewegen is het bedienen van de telefoon (`docs/SCREEN.md`).

## Wat erbij kwam in 0.1.73 (de gedeelde muis onderweg, nog niet op echte toestellen gezien)

- **Levensteken.** De computer die de aanwijzer heeft stuurt elke halve seconde `Ping`; de ander antwoordt `Pong`. Zwijgt de ander twee
  seconden (klep dicht, wifi weg), dan haalt de hoofdcomputer de aanwijzer zelf terug (`PING_EVERY_MS`, `PING_PATIENCE_MS`), zonder
  te wachten tot de verbinding zelf afloopt. De bestuurde kant laat de aanwijzer los na 3,5 seconde stilte en laat alles los wat vastzat.
- **Schermgrootte.** De bestuurde computer meldt direct na `Enter` zijn schermgrootte (`Size`). De hoofdcomputer volgt daarmee waar de
  aanwijzer daar is (Mac: `RemoteTracker`, Rust: `Controlled::enter_counting`) en haalt hem naar huis als hij er voorbij de ingang weer uit
  geduwd wordt, ook als de bestuurde kant de echte plek niet kent of niet meldt. Op Linux zet de bestuurde kant de aanwijzer zelf op de
  getelde plek (absoluut), want de echte plek is onder Wayland (XWayland) niet te vertrouwen en X versnelt relatieve bewegingen nog eens.
- **Slepen.** Gaat de aanwijzer over de rand terwijl de linkerknop vastzit (een foto, bestand of geselecteerde tekst), dan gaan de
  bestanden mee als aanbod met herkomst `Drag` (een afbeelding wordt eerst een bestand) en tekst als `Carry`. De bestuurde kant zet bestanden neer als de
  knop omhoog komt, en plakt de tekst op de plek van de aanwijzer (Linux: selectie plus middelste klik; elders: klik plus Ctrl+V). De
  Mac beeindigt zijn eigen sleep met Escape, nu meteen bij de rand (0.1.77), zodat het plaatje niet op de rand blijft hangen, en de pill onderin toont wat er meegaat (plaatje of icoon en naam) en daarna dat het is neergezet. Een echte sleep van het ene besturingssysteem naar het andere bestaat niet (dat kan alleen Universal Control tussen Apple-apparaten): de inhoud reist mee als bestand of tekst. Van Linux naar de Mac kan het niet vanzelf, want onder Wayland is niet te zien wat er gesleept wordt; daar werkt de randzone (sleep op de rand) wel. Een sleep die tegen de ingangsrand komt blijft daar staan (boven de dropzone) tot de knop omhoog is.
- **Klep.** Op Linux met een klep (`/proc/acpi/button/lid` of systemd `LidClosed`): klep dicht geeft de aanwijzer terug en weigert nieuwe,
  tenzij de instelling "Bruikbaar houden met de klep dicht" aanstaat (standaard uit). Ook de telefoonbediening volgt dat.

## Wat erbij kwam na 0.1.73 (Linux als hoofdcomputer, nog niet op echte toestellen gezien)

- **Linux (Wayland) als hoofd.** `windows/src-tauri/winsys/src/inputcapture.rs` gebruikt de InputCapture-portal van het bureaublad (GNOME 45 en
  nieuwer, KDE Plasma 6.1 en nieuwer): Tandem zet barrieres op de stukken van de randen waar een andere computer zit (`barriers_for`, met tests),
  en als de aanwijzer er tegenaan loopt geeft het bureaublad de muis en het toetsenbord aan Tandem, via libei (de crate `reis`). Die
  bewegingen, knoppen, scrollen en toetsen (evdev, vertaald naar Windows-codes en dan naar Mac-codes) gaan dezelfde weg als wat de hooks van
  Windows zien (`capture.rs`, `see`). Terug gaat met `Leave` van de ander, met Ctrl, Alt en Shift met Escape, of doordat het
  bureaublad de muis zelf terugneemt. De eerste keer vraagt het bureaublad om toestemming. Een portal van versie 2 onthoudt het antwoord
  (`portal-capture.token`); GNOME 50 meldt versie 1 (gezien op een Fedora 44), dan vraagt het bureaublad het bij elke start van Tandem opnieuw. Onder Instellingen, Gedeelde muis staat een regel zolang die vraag openstaat. Niet voor X11.
- **Wayland-invoer herstelt zichzelf.** Sluit het bureaublad de portal-sessie (scherm op slot, een nieuwe vraag), dan opent
  de bestuurde kant een nieuwe met het onthouden antwoord. Lukt dat niet, dan geeft hij de aanwijzer meteen terug aan de hoofdcomputer en
  meldt hij in het venster en met een melding welke toestemming ontbreekt.
- **Mac.** De pill onderin blijft staan zolang de aanwijzer op een andere computer is en toont de toetsen (control, option, command, esc).

## Wat er nog moet, per systeem

1. **Mac als hoofdcomputer:** een `CGEventTap` die de muis en de toetsen ziet (heeft Toegankelijkheid en Invoercontrole nodig, de
   eerste is er al voor het afspelen), de eigen aanwijzer verbergen (`CGDisplayHideCursor`) en loskoppelen van de muis
   (`CGAssociateMouseAndMouseCursorPosition(false)`), de evenementen opslokken zolang de ander de aanwijzer heeft, en de bewegingen
   als `InputMsg` sturen. Een toets om terug te halen (standaard Control Option Command en de rand zelf).
2. **Mac als bestuurde computer:** de bestaande afspeller voor de telefoon gebruiken, en met `Controlled` bijhouden waar de
   aanwijzer is en wanneer hij terug moet.
3. **Windows als hoofd en als bestuurd:** laag-niveau hooks (`WH_MOUSE_LL`, `WH_KEYBOARD_LL`) in `winsys` om te zien en op te slokken,
   `SendInput` of `enigo` om af te spelen. Schermgroottes in pixels, let op meer schermen en schaling.
4. **Linux:** afspelen via libei of XTest (X11) lukt. Zien van invoer vraagt layer-shell of libei op Wayland en evdev of
   XInput op X11, en rechten die per bureaublad verschillen. Eerst alleen bestuurd worden, dan pas hoofd zijn.
5. **Instellingen en uitleg:** per apparaat een plek (links, rechts, boven, onder) kiezen, aan en uit zetten, de toets om terug
   te halen, en duidelijk zeggen welke rechten het systeem vraagt. Het klembord loopt al mee.
6. **Toetsen:** de toetscodes van `InputMsg::Key` zijn nu die van de Mac. Een ander systeem vertaalt (zoals de Windows-afspeler
   al voor de telefoon doet). Tekst met accenten gaat als `Text`.

## Waarom dit niet in één keer kan

Elk onderdeel hierboven vraagt rechten van het systeem en kan alleen echt worden uitgeprobeerd met twee computers naast elkaar,
bij voorkeur van elk besturingssysteem. De kern en de tests staan; het zichtbare deel (de rand, de verborgen aanwijzer) moet per
systeem worden gebouwd en gezien.
