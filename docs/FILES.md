# Bestanden van een ander apparaat

Een apparaat in de circle kan de bestanden van een ander apparaat bekijken, lezen en wijzigen: de Mac bladert door de
telefoon, later ziet de Bestanden-app van Android de Mac en de pc. Dit is iets anders dan delen (`ShareOffer`): daar
stuurt de een iets naar de ander, hier gaat de een zelf kijken. De code staat in `crates/tandem-core/src/files.rs`.

## Wie beslist

Het apparaat waar naar gekeken wordt. Dat beslist in de core, niet in de app, dus een fout in een scherm kan er niets
aan openzetten. Per apparaat (of als standaard voor apparaten zonder eigen keuzes) staat vast:

| Instelling | Standaard | Wat het doet |
|---|---|---|
| `enabled` | aan | De hoofdschakelaar. Uit: het andere apparaat ziet niets en krijgt te horen waarom. |
| `shares` | Downloads, Documenten en Bureaublad (Android: de hele telefoon) | De mappen die worden aangeboden, met een naam. Geen mappen: niets te zien. |
| `write` | aan | Maken, overschrijven en hernoemen. |
| `delete` | aan | Verwijderen. |
| `hidden` | uit | Bestanden en mappen die met een punt beginnen, en wat Windows verborgen noemt. |
| `max_upload` | 0 (geen limiet) | Het grootste bestand dat het andere apparaat hier mag neerzetten. |

Elke gedeelde map heeft ook een eigen `write`. Die kan alleen beperken wat het beleid toestaat, nooit verruimen.
Lezen en schrijven staan standaard aan, zoals gevraagd. De circle bestaat alleen uit eigen apparaten waarvan de sleutel is
vastgezet, en elk ander apparaat komt er niet in.

De laatste 200 dingen die een ander apparaat deed (schrijven, verwijderen, verplaatsen, en elke weigering) staan in
`Engine::file_activity`, nieuwste eerst. Kijken staat er niet in.

## Op de lijn

Een verzoek is een stream. De eerste byte is `STREAM_FS` (3), dan een `FsRequest` als frame, en bij een schrijfactie de
bytes van het bestand. Het antwoord is een `FsResponse` als frame, en bij een leesactie komen de bytes erna.

| Verzoek | Antwoord |
|---|---|
| `Roots` | `Roots`: de aangeboden mappen en of ze te wijzigen zijn |
| `List { path, offset, limit }` | `Entries { items, more }`, op naam gesorteerd, maximaal 4000 per antwoord |
| `Stat { path }` | `Entry` |
| `Read { path, offset, len }` | `Data { size, modified_ms, sent }` en dan `sent` bytes |
| `Write { path, size, overwrite }` | de bytes volgen op het verzoek, dan `Ok` |
| `Mkdir`, `Remove { recursive }`, `Rename { overwrite }` | `Ok` |

Een pad is `/Naam/map/bestand`. `/` zelf is de lijst met aangeboden mappen. Fouten komen terug als `Err { code,
message }` met een van: `not_found`, `denied`, `disabled`, `outside`, `exists`, `not_empty`, `not_dir`, `is_dir`,
`too_large`, `unsupported`, `io`.

Een bestand komt heel aan of helemaal niet: een schrijfactie gaat eerst naar `.naam.<getal>.tandem-part` naast de
bestemming en krijgt zijn naam pas als alle bytes er zijn. Een download gaat naar `naam.tandem-part` en wordt
hervat vanaf wat daar al staat. Gaat het fout nadat een leesactie is begonnen, dan breekt de host de stream af in
plaats van een foutframe tussen de bytes te zetten, want dat zou voor bestandsinhoud worden aangezien.

## Wat er niet door kan

- Een pad dat niet met een slash begint, een deel `.` of `..`, een backslash, een nulbyte of een stuurteken. Op Windows
  ook een dubbele punt, een naam die eindigt op een punt of spatie, en de namen van het systeem (CON, NUL, COM1).
- Een koppeling op de schijf die buiten de gedeelde map uitkomt. Elk pad wordt na het volgen van koppelingen gecontroleerd
  tegen de gedeelde map, ook voor bestanden die nog niet bestaan (dan geldt de map waarin ze zouden komen) en voor een
  koppeling die nergens heen wijst. Zulke koppelingen staan ook niet in een lijst.
- Verborgen dingen als `hidden` uit staat, ook niet als iemand het exacte pad kent.
- De gedeelde map zelf verwijderen of verplaatsen, en iets van de ene gedeelde map naar de andere verplaatsen.

Bekend en geaccepteerd: tussen het controleren en het gebruiken van een pad kan iemand met schrijftoegang op het
apparaat zelf nog een koppeling verleggen. Wie dat kan, heeft al toegang tot de bestanden.

## Per platform

- **Android** moet toegang tot alle bestanden hebben (`MANAGE_EXTERNAL_STORAGE`), anders mislukt elk verzoek met `denied`.
  De app laat dat zien en brengt je naar de systeeminstelling.
- **macOS** vraagt zelf toestemming voor Documenten, Bureaublad en Downloads zodra Tandem er voor het eerst in kijkt.
- **Windows** gebruikt dezelfde code. De namen van het systeem zijn er geweigerd.
- Apparaten met een oudere versie bieden niets aan: `Hello.caps` bevat `files` alleen bij versies die het kennen.

## Op de opdrachtregel

`tandemd ls <apparaat> [pad]`, `get`, `put`, `mkdir`, `rm`, `mv`: werken tegen een draaiende daemon of voeren zelf uit.
