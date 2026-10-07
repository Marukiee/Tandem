# Wat werkt waar

De tabel van sectie 8 van docs/ROADMAP.md: per functie en per systeem of het er is. De doelstelling van Mark (2026-10-06): alles wat
er voor de Mac is moet ook op Windows en Linux werken, en tussen alle systemen onderling (Linux naar Windows, Windows naar de Mac, en
andersom), niet alleen telefoon naar computer. Een kruisje betekent dat het er nog niet is, een streepje dat het niet past bij dat systeem.

Betekenis: `ja` gebouwd (en nergens op een echt toestel gezien tenzij er `bekeken` staat), `deels` er is een stuk van, `nee` ontbreekt.
Deze tabel is gemaakt vanuit de code van 2026-10-07 en moet na elke versie bijgewerkt worden.

| Functie | Android | Mac | Windows | Linux |
| --- | --- | --- | --- | --- |
| Koppelen met QR, link en code van 8 cijfers, uitnodigen | ja, bekeken (emulator) | ja | ja | ja |
| Bestanden, klembord, links, tekst sturen en ontvangen | ja | ja | ja | ja |
| Meldingen van de telefoon tonen op de computer | bron | ja | ja | ja |
| Antwoorden op meldingen, bellen en gemiste oproepen tonen | bron | ja | deels (toon) | deels (toon) |
| Muziek van de telefoon bedienen vanaf de computer | bron | ja | ja (mediatoetsen van het systeem) | ja (MPRIS, getest op een sessiebus in CI) |
| Klembordgeschiedenis | ja | ja | ja | ja |
| Quick Share (ontvangen en versturen, tekst en links) | ja | ja | ja | ja |
| Bestanden van een telefoon bekijken en kopieren | bron | ja | ja | ja |
| Eigen mappen aanbieden aan andere apparaten (host) | ja | ja | ja (instellingen, nooit gezien) | ja (instellingen, nooit gezien) |
| Telefoonscherm en camera tonen | bron | ja, bekeken (emulator) | ja | ja (eigen decoder, nooit gezien) |
| De telefoon bedienen met het getoonde scherm | bron | ja | ja (knop, nooit gezien) | ja (knop, nooit gezien) |
| Invoegen vanaf telefoon (scan, foto) | bron | ja | ja (op het klembord, nooit gezien) | ja (op het klembord, nooit gezien) |
| Geluid van de telefoon op de computer | bron | ja | ja (nooit gehoord) | ja (nooit gehoord) |
| Scherm van de computer tonen op de telefoon en op andere computers | viewer | ja (host) | nee | nee |
| De computer bedienen vanaf de telefoon (trackpad, toetsenbord) | bron | ja | ja | deels (alleen X11) |
| Een computer bedienen vanaf een andere computer (gedeelde muis) | nee | ja (hoofd en bestuurd) | ja (hoofd en bestuurd) | nee (bestuurd alleen op X11, geen hoofd) |
| Bestanden slepen over de rand bij de gedeelde muis | nee | nee | nee | nee |
| SSH-terminal naar een computer | nee | ja | ja | ja |
| Hotspot van de telefoon vanaf de computer | bron | ja | nee | nee |
| Computer wakker maken (Wake on LAN) vanaf de telefoon | ja | doel | doel | doel |
| Zoek mijn telefoon | bron | ja | ja | ja |
| Bijwerken | ja | ja | ja | nee (eigen pad) |

## Wat tussen computers onderling nog moet

- Linux en Windows moeten dezelfde functies als de Mac kunnen **aanbieden**, niet alleen tonen: eigen mappen (host), eigen scherm
  delen (Windows.Graphics.Capture, PipeWire), geluid delen, Invoegen vanaf telefoon als bron voor een andere computer.
- Linux: invoer onder Wayland (libei), de gedeelde muis als hoofd (X11 `XInput2`, Wayland InputCapture), een eigen updatepad.
- Bestanden slepen over de rand: nog nergens.
- De Mac-functies die aan macOS zelf vastzitten horen er niet bij: het menu Diensten, het Controlecentrum, het AirDrop-knopje.

Alles op Linux en Windows moet na het regelen van toegang tot de Linux-pc van Mark nagelopen worden, en op een echte Windows-pc.
