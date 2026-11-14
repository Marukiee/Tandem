# Overdrachten en het overdrachtsboek

Bestanden sturen gaat met een aanbod (`ShareOffer`): de afzender biedt aan, de ontvanger haalt elk bestand zelf op
(`FileRequest` met een offset), zie `docs/PROTOCOL.md`. Sinds P1 (2026-11-14) staat elk aanbod, in en uit, in het
overdrachtsboek in de datamap: wat erin zit, voor wie, en hoe ver elk bestand is. Daarop bouwen hervatten na een herstart
(P2), de wachtrij (P3), het paneel Onderweg en Inkomend (P3b) en echte mappen (P4). Code: `crates/tandem-core/src/journal.rs`
(het boek) en `transfer.rs` (sturen en ontvangen). Tests: `crates/tandem-core/tests/transfers.rs` en de tests onderin
`journal.rs`.

## Het formaat

Een bestand per aanbod in `<datamap>/transfers/`: `out-<aanbod>.cbor` voor wat dit apparaat stuurt, `in-<afzender>-<aanbod>.cbor`
voor wat binnenkomt (het nummer is 16 hex-tekens; een binnenkomend nummer kiest de afzender, dus de afzender hoort bij de
sleutel). Elk bestand is een `TransferRecord` in CBOR, net als de andere kleine bestanden in de datamap, en wordt atomair
geschreven met `Store::write`.

| Veld | Wat |
| --- | --- |
| `format` | de opmaak die het schreef, nu `1` |
| `offer`, `incoming` | het nummer en de richting |
| `origin` | `ShareOrigin` (bestanden, klembord, schermafbeelding, ...) |
| `created_ms`, `updated_ms` | klok in ms: wanneer gemaakt, wanneer de stand laatst veranderde |
| `items[]` | per bestand: `name`, `size`, `mime`, `modified` (zoals in het aanbod), `source` (uit: pad of content-URI), `source_modified_ms` (uit: wanneer de bron veranderde toen het aanbod gemaakt werd, als dat bekend is), `path` (map binnen het aanbod, voor P4, nu leeg) |
| `peers[]` | uit: een per ontvanger; in: een, de afzender. Per apparaat `device`, `stage`, `offered_ms` en `items[]` |
| `peers[].items[]` | per bestand voor dat apparaat: `state`, `done` (bytes), `error`, `location` (in: waar het staat) |
| `held` | in: wachtte op een ja omdat dit apparaat op mobiele data zat en het groot was |

`stage` per apparaat: `queued` (P3: nog niet verstuurd, het apparaat was weg), `offered` (verstuurd of binnen, nog niets
opgehaald; binnenkomend wacht het op een ja), `accepted` (ja gezegd, of de ontvanger haalde op), `closed` (gestopt, geweigerd
of verlopen; er wordt niets meer bediend of opgehaald), `unknown`.

`state` per bestand: `waiting`, `running`, `interrupted` (halverwege gestopt, het deelbestand bleef staan), `done`, `failed`,
`cancelled`, `declined`, `expired`, `unknown`. De laatste vijf voor `unknown` zijn eindstanden.

### Waarom zo

- **Een bestand per aanbod**, niet een groot bestand: een wijziging herschrijft alleen dat aanbod, een kapot bestand kost alleen
  dat aanbod, en weg is gewoon het bestand weghalen. Er zijn er nooit veel (zie de bewaartijden).
- **Oude en nieuwe versies lezen elkaars bestanden.** CBOR met veldnamen, elk veld behalve de kern heeft `#[serde(default)]`,
  en de standen zijn tekst met `unknown` als vangnet. Een nieuw veld of een nieuwe stand is dus geen nieuw formaat. Pas als iets
  echt anders gelezen moet worden gaat `format` omhoog; een bestand met een hoger `format` laat een oudere versie staan (terug en
  weer vooruit verliest niets).
- **Een kapot bestand houdt de start niet tegen** (anders dan `circle.cbor`): het wordt hernoemd naar `.bad`, de engine start
  zonder dat aanbod, en `.bad` en onleesbare bestanden gaan na 7 dagen weg.
- **Klok in ms, nooit `Instant`**: een `Instant` overleeft geen herstart.
- **Dezelfde vorm in en uit**: apparaten maal bestanden. Uit heeft er een rij per ontvanger (R-B8 en het paneel hebben dat
  nodig), in een rij.
- **Naar schijf gaat elke verandering van stand en elk einde van een bestand.** Niet naar schijf: `running` en de bytes onderweg,
  die staan alleen in het geheugen (de engine werkt ze bij met de voortgang). Bij de start wordt alles wat liep `interrupted`, en
  voor binnenkomende bestanden komt `done` uit de lengte van het deelbestand: dat is waar een hervatting begint, en zo is er geen
  schrijfbeurt per stukje. Een binnenkomend aanbod dat al ja had en bestanden die nog op een vrije plek wachtten, worden ook
  `interrupted`: ze waren onderweg.
- **Geschreven terwijl het boek op slot zit**: twee wijzigingen van een aanbod komen nooit in de verkeerde volgorde op schijf, en
  wat in het geheugen staat staat ook op schijf. Een schrijfbeurt kost een fsync; daarom alleen bij een verandering van stand.
- **Wat alleen een draaiende engine heeft blijft erbuiten**: of het ophalen begon (`started`) en de stoptekens. Uit heeft per
  ontvanger een eigen stopteken, een kind van dat van het aanbod, zodat stoppen naar een ontvanger de rest laat lopen (R-B8).
- **Het deelbestand heeft een vaste naam**: `<datamap>/incoming/<afzender>-<aanbod>-<nummer>.part` (`journal::partial_name`).
  Het boek hoeft het pad dus niet te bewaren.

### Bewaartijden (`journal.rs`)

| Wat | Hoe lang | Daarna |
| --- | --- | --- |
| een aanbod dat op een ja wacht (`offered`) | 6 uur na binnenkomst of versturen | `expired`; de ontvanger zegt het de afzender (`ShareReply` met reden `expired`) |
| halverwege gestopt (`accepted` met bestanden die niet af zijn, niets loopt) | 3 dagen na de laatste verandering | `expired`, deelbestanden weg |
| afgelopen (alles in een eindstand) | 6 uur na de laatste verandering, hooguit 200 | weg uit het boek |

Een binnenkomend aanbod dat geweigerd is of verlopen gaat meteen weg: daar valt niets meer aan te zien. Een deelbestand waar geen
aanbod in het boek naar wijst gaat bij de start weg (van voor het boek, of van een crash ertussen); de andere gaan met hun aanbod
mee (R-B27).

### Redenen in `Finished`

Een bestand dat eindigt zonder aan te komen krijgt in `TransferDone::error` een kort woord dat de apps mogen vertalen, zoals nu al
`cancelled`: `cancelled`, `declined` (de ontvanger zei nee, R-B14), `expired`. De afzender krijgt bij een nee of een verlopen
aanbod per bestand een `Finished` met `incoming: false`. De reden komt van de ontvanger (`ShareReply::reason`), `declined` als die
geen reden geeft. Een volgend pakket kan zo ook `no space` sturen (P5) zonder nieuw bericht. Elk bestand krijgt zo'n `Finished`
een keer: alleen de overgang naar de eindstand geeft er een. De apps tonen `declined` en `expired` nu nog als tekst; ze kunnen ze
vertalen zoals `cancelled`.

### Besluiten

- **Een afzender die opnieuw start bedient zijn bewaarde aanbiedingen.** Een ontvanger die nog aan het ophalen was (die probeert
  het 10 minuten opnieuw) krijgt de rest gewoon. De regel van Mark, nooit vanzelf verder, gaat over de ontvanger: die haalt na een
  herstart pas weer op als de persoon ja zegt (P2). Op Android verloopt de toestemming voor een bestand uit een andere app na een
  herstart, dan antwoordt de afzender `could not open the file`; dat lost P3 op met de kopie in `outgoing/`.
- **Alles komt in het boek**, ook klembord, foto's en scans (`Capture`) en slepen (`Drag`). Zulke aanbiedingen horen bij een
  moment: P2 biedt ze niet aan om te hervatten maar gooit ze bij de start weg.
- **`accept_offer` start alleen een aanbod dat op een ja wacht.** Op een overdracht die halverwege stopte geeft het een fout: die
  gaat alleen verder via P2.
- **Een herhaald aanbod met hetzelfde nummer** van dezelfde afzender wordt overgeslagen; de nummers zijn willekeurig, dus het is
  hetzelfde aanbod twee keer.

## De pakketten die erop bouwen

De functies hieronder zijn een voorstel voor `ffi.rs`; P1 voegde daar niets toe. `Engine::transfers()` (alleen in Rust) geeft het
hele boek, het nieuwste eerst.

### P2. Hervatten na een herstart

Na de start loopt er niets. De app vraagt bij de start `transfers()` en vindt elke binnenkomende overdracht met bestanden in
`interrupted`; daar vraagt hij "Verder gaan of weggooien?". Geen nieuwe gebeurtenis nodig.

- **Ja**: `resume_transfer(from, offer)` zet `started` en haalt de bestanden op die niet `done` zijn, vanaf de lengte van het
  deelbestand (`receive_all` slaat `done` al over, `pull_item` begint al bij het deelbestand).
- **Nee**: `cancel_transfer(from, offer, true)` doet het al: deelbestanden weg, bestanden `cancelled`, en de afzender krijgt
  `ShareCancel` en sluit die ontvanger. Nog zonder test.
- **"Dit aanbod opnieuw"**: met het boek heeft een afzender die zelf herstartte het aanbod nog, en het ophalen van de rest is zelf
  de vraag. Heeft de afzender het niet meer (verlopen, een oude versie die herstartte), dan antwoordt die met
  `FileResponse { ok: false, error: "that offer is no longer available" }`; dat wordt nu een `failed`. P2 moet dat als eigen
  geval melden ("de afzender heeft het niet meer") en het deelbestand weggooien. Een apart bericht is alleen nodig als P2 vooraf
  wil vragen of het er nog is; voorstel: niet doen.
- Wat bij een moment hoort (`Clipboard`, `Capture`, `Drag`) bij de start zonder vraag weggooien.

Klaar als een kerntest dekt: ja haalt de rest op en het bestand klopt; nee gooit het deelbestand weg en de afzender hoort het;
de afzender heeft het niet meer en dat komt als eigen melding; een klembordaanbod wordt niet aangeboden.

### P3. Wachtrij voor apparaten die weg zijn

- `send_files` zet ontvangers die offline zijn in het boek met `stage: queued` en `offered_ms: 0` (nu laat het ze weg). Bij
  `Connected` (`session.rs`) gaat voor elk aanbod met die ontvanger in `queued` het aanbod uit: `offered`, `offered_ms` nu.
- Voor het versturen elke bron openen en `size` en `source_modified_ms` vergelijken; weg of veranderd: die bestanden voor die
  ontvanger `failed` met een reden (`source gone`, `source changed`), een `Finished` per bestand, en de rest gaat wel.
- Een nieuwe bewaartijd `QUEUED_KEPT` van 7 dagen sinds `created_ms`, daarna `expired` met een `Finished`. Ook de
  opruiming van `outgoing/` (nu 24 uur) mag geen bestand weghalen waar een aanbod in het boek naar wijst.
- Android: een bestand van een andere app eerst naar `outgoing/` kopieren, want de toestemming verloopt.
- `SendReport`: een veld `queued`, met `#[uniffi(default = None)]` (of een lege lijst als standaard).

Klaar als een kerntest dekt: het aanbod gaat uit zodra de ontvanger verbindt en komt heel aan; na 7 dagen verloopt het met een
`Finished`; een veranderd en een verdwenen bestand worden gemeld en niet gestuurd; een herstart van de afzender houdt de
wachtrij.

### P3b. Het paneel Onderweg en Inkomend

Voorstel voor `ffi.rs`: `fn transfers(&self) -> Vec<TandemTransfer>`, een record dat `TransferRecord` volgt
(`offer`, `incoming`, `origin`, `created_ms`, `updated_ms`, `items`, `peers` met de apparaat-id als tekst, `held`) en twee enums
`TandemTransferStage` en `TandemItemState` met dezelfde namen als hierboven, plus `Unknown`. De app vraagt de lijst opnieuw bij
`ShareOffered`, `Finished` en (beperkt, hooguit een paar keer per seconde) `Progress`; een nieuwe gebeurtenis is dan niet nodig,
en zonder nieuwe variant breekt geen enkele `switch` in de apps. Knoppen: Stop is `cancel_transfer`, Verder gaan en Weggooien zijn
die van P2, Opnieuw bij een mislukt uitgaand bestand stuurt dat bestand als nieuw aanbod.

Klaar als: de kerntest een lopende, een wachtende, een halve, een wachtrij- en een mislukte overdracht in `transfers()` terugziet
met de juiste stand, en de Mac het paneel toont met alle vijf.

### P4. Echte mappen

`ShareItem` krijgt `path` (`#[serde(default)]`), met een nieuwe cap; een oude ontvanger krijgt de zip zoals nu. In het boek staat
het al: `RecordItem::path`. De ontvanger maakt de mappen, elk deel door de naamcontrole (R-V6). Het deelbestand blijft per nummer,
dus hervatten werkt voor mappen vanzelf.

Klaar als een kerntest drie lagen, een lege map en een naam die naar buiten wil dekt, en een oude ontvanger nog de zip krijgt.

## Na R-V7 voor de Android-app

De kern leest een bestand nu op positie (`read_at`), nooit via de leespositie van de descriptor. `AndroidFiles.openRead` hoeft
een kopie van een vastgehouden descriptor dus niet meer terug te zetten (`Os.lseek`), en twee apparaten kunnen hetzelfde bestand
tegelijk ophalen (R-B6).
