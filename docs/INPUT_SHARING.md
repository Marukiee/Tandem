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
