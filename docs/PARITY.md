# Wat werkt waar

De tabel van sectie 8 van docs/ROADMAP.md: per functie en per systeem of het er is. De doelstelling van Mark (2026-10-06): alles wat
er voor de Mac is moet ook op Windows en Linux werken, en tussen alle systemen onderling (Linux naar Windows, Windows naar de Mac, en
andersom), niet alleen telefoon naar computer. Een kruisje betekent dat het er nog niet is, een streepje dat het niet past bij dat systeem.

Betekenis: `ja` gebouwd (en nergens op een echt toestel gezien tenzij er `bekeken` staat), `deels` er is een stuk van, `nee` ontbreekt.
Deze tabel is voor het laatst nagelopen tegen de code van 0.1.84 (2026-10-16) en moet na elke versie bijgewerkt worden. Van Windows en Linux is bijna niets op een echt toestel gezien.

| Functie | Android | Mac | Windows | Linux |
| --- | --- | --- | --- | --- |
| Koppelen met QR, link en code van 8 cijfers, uitnodigen | ja, bekeken (emulator) | ja | ja | ja |
| Bestanden, klembord, links, tekst sturen en ontvangen | ja | ja | ja | ja |
| Meldingen van de telefoon tonen op de computer | bron | ja | ja | ja |
| Antwoorden op meldingen, bellen en gemiste oproepen tonen | bron | ja (zwevend belvenster) | deels (meldingen als toon; zwevend belvenster met Opnemen, Weigeren en Ophangen, nooit gezien) | deels (idem; onder Wayland kiest het bureaublad de plek van het belvenster en houdt het niet bovenop) |
| Muziek van de telefoon bedienen vanaf de computer | bron | ja | ja (mediatoetsen van het systeem) | ja (MPRIS, getest op een sessiebus in CI) |
| Klembordgeschiedenis | ja | ja | ja | ja |
| Quick Share (ontvangen en versturen, tekst en links) | ja | ja | ja | ja |
| Bestanden van een telefoon bekijken en kopieren | bron | ja | ja | ja |
| Eigen mappen aanbieden aan andere apparaten (host) | ja | ja | ja (instellingen, nooit gezien) | ja (instellingen, nooit gezien) |
| Telefoonscherm en camera tonen | bron | ja, bekeken (emulator) | ja | ja (eigen decoder, nooit gezien) |
| De telefoon bedienen met het getoonde scherm | bron | ja | ja (knop, nooit gezien) | ja (knop, nooit gezien) |
| Invoegen vanaf telefoon (scan, foto) | bron | ja | ja (op het klembord, nooit gezien) | ja (op het klembord, nooit gezien) |
| Geluid van de telefoon op de computer | bron | ja | ja (nooit gehoord) | ja (nooit gehoord) |
| Scherm van de computer tonen aan andere apparaten en het laten bedienen (zoals TeamViewer) | kijkt en bedient | ja (host en kijker) | ja (host, GDI en OpenH264, kijker) | ja onder X11 (host, nooit gezien); onder Wayland geeft de portal met GStreamer beeld (op een echte laptop gezien als debugbeeld, delen met een kijker nog niet); kijker ja, het scherm van een Mac gezien in een venster op een echte laptop (0.1.75) |
| De computer bedienen vanaf de telefoon (trackpad, toetsenbord, aanraken) | bron | ja | ja | ja: X11, en onder Wayland via een portalsessie met het beeld (0.1.83, nooit gezien) |
| Een computer bedienen vanaf een andere computer (gedeelde muis) | nee | ja (hoofd en bestuurd) | ja (hoofd en bestuurd) | deels: bestuurd (X11 en Wayland met de RemoteDesktop-portal), hoofd alleen onder Wayland via de InputCapture-portal (nooit gezien), niet onder X11 |
| Bestanden slepen over de rand bij de gedeelde muis | nee | ja, beide kanten (dropzone aan de rand, bestanden landen in het Finder-venster of op het bureaublad) | ja als bestuurde pc (dropzone, landen op het bureaublad), niet als hoofd | idem als Windows, onder X11 |
| Een sleep van foto, bestand of tekst gaat mee over de rand (Mac als hoofd), op de plek van de aanwijzer | nee | ja, als hoofd (tekst plakt op de plek, bestanden landen na het loslaten) | ja als bestuurde pc (tekst: klik en plakken) | ja als bestuurde pc (tekst: middelste klik, bestanden op het bureaublad) |
| De aanwijzer komt direct terug als de verbinding wegvalt of de klep dichtgaat (levensteken, standaard 2 seconden) | nee | ja | ja (hoofd en bestuurd) | ja (bestuurd), klep via instelling |
| Tailscale gaat vanzelf aan als een apparaat niet bereikbaar is en Tailscale het zou bereiken | ja (melding aan de Tailscale-app) | ja (`tailscale up`) | ja (`tailscale up`) | ja (`tailscale up`, vraagt eenmalig `--operator`) |
| Meerdere schermen naast de hoofdcomputer, te slepen en op schaal (zoals de weergave-instellingen van een desktop) | nee | ja, als hoofd (`ArrangementEditor`) | ja, als hoofd (`arrange.js`) | ja onder Wayland als hoofd (zelfde `arrange.js`, nooit gezien), niet onder X11 |
| De telefoon als scherm voor de gedeelde muis (aanwijzer als overlay, tikken via Toegankelijkheid) | ja (`PointerIn`, nooit op een echte telefoon gezien) | als hoofd ja | als hoofd ja (`arrange.js`, nooit gezien) | als hoofd ja onder Wayland (nooit gezien) |
| Muis, toetsen en scherm onder Wayland via de portals van het bureaublad (eenmalige toestemming die wordt onthouden) | n.v.t. | n.v.t. | n.v.t. | invoer gezien (token bewaard); beeld, tweede scherm en bediening in een sessie sinds 0.1.82 en 0.1.83, nooit gezien |
| SSH-terminal naar een computer | ja (JSch en xterm.js, nooit tegen een echte computer geprobeerd) | ja | ja | ja |
| Hotspot van de telefoon vanaf de computer | bron | ja | nee (heeft geen Bluetooth-code) | nee (Bluetooth is er sinds 0.1.82, het verzoek niet) |
| Tweede scherm (een computer maakt een scherm voor een ander apparaat, ook aan te vragen vanaf de computer) | kijker (raakt aan = klikken) | host (virtueel beeldscherm) en kijker | kijker, geen host (stuurprogramma nodig) | host onder Wayland (met aanraken, nooit gezien) en kijker |
| Bluetooth tussen apparaten zonder netwerk (klembord, meldingen) | wacht (telefoon) | wacht en zoekt | nee | wacht en zoekt (BlueZ, nooit tussen twee computers gezien) |
| Computer wakker maken (Wake on LAN) vanaf de telefoon | ja | doel | doel | doel |
| Zoek mijn telefoon | bron | ja | ja | ja |
| Zoek mijn computer (geluid uit de eigen systeemgeluiden, laatst gezien met wifinaam) | ja (vraagt, met laatst gezien) | ja, beide kanten | ja, beide kanten | ja, beide kanten |
| Bijwerken | ja | ja | ja | ja als AppImage; een pakket van de distributie werkt de pakketbeheerder bij |
| Contacten van de telefoon in het zoeken van de computer, kiezen belt ze | bron (`sms.contacts`) | ja (Spotlight via Core Spotlight; nep-contacten gevonden met een `CSSearchQuery`, nooit met een echte telefoon) | - (zie hieronder) | ja: GNOME-zoeken (alleen met het .deb of .rpm, niet de AppImage) en KRunner; in CI op een sessiebus, nooit op een echt bureaublad |

## Wat er nog mist

Wat niet te bouwen is zonder toestel of stuurprogramma, en wat vraagt om een echte test:

- **Windows**: een scherm voor een ander apparaat maken (een stuurprogramma voor een virtueel beeldscherm, eenmalig met beheerdersrechten), Bluetooth
  tussen apparaten (WinRT), het hotspotverzoek aan de telefoon. En alles nalopen op een echte Windows-pc: dat is nooit gedaan.
- **Linux**: het hotspotverzoek aan de telefoon (de Bluetooth-kant is er), de gedeelde muis als hoofd onder X11 (`XInput2` met grabs), en
  Bluetooth, tweede scherm, bediening onder Wayland en de melding bij slechte verbinding nalopen op de echte laptop.
- **Overal**: Windows en Linux moeten dezelfde functies als de Mac kunnen aanbieden, niet alleen tonen: geluid van een computer naar een andere
  computer en Invoegen vanaf telefoon als bron voor een andere computer.
- De Mac-functies die aan macOS zelf vastzitten horen er niet bij: het menu Diensten, het Controlecentrum, het AirDrop-knopje.
- **Contacten in het zoeken van Windows** kan niet: het zoeken van het Startmenu toont apps, instellingen, bestanden en het web, en heeft
  geen manier voor een app om eigen resultaten te geven (de zoekconnectors van Windows 7 werken alleen in de Verkenner). Een bestand per
  contact in een geïndexeerde map zou als document verschijnen, niet als iemand die je belt. Op Windows haalt Tandem de lijst dus niet op;
  bellen gaat met de knop Bellen op de pagina van de telefoon, waar een naam ook werkt.
- **Linux, GNOME**: GNOME leest zoekleveranciers alleen uit `/usr/share/gnome-shell/search-providers`, niet uit de map van de gebruiker. Het
  .deb en .rpm zetten het bestand daar; de AppImage kan dat niet, KRunner werkt er wel mee. Een nieuwe leverancier ziet GNOME pas na opnieuw
  inloggen.
