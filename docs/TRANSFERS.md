# Overdrachten en het overdrachtsboek

Bestanden sturen gaat met een aanbod (`ShareOffer`): de afzender biedt aan, de ontvanger haalt elk bestand zelf op
(`FileRequest` met een offset), zie `docs/PROTOCOL.md`. Sinds P1 (2026-11-14) staat elk aanbod, in en uit, in het
overdrachtsboek in de datamap: wat erin zit, voor wie, en hoe ver elk bestand is. Daarop bouwen hervatten na een herstart
(P2), de wachtrij (P3), het paneel Onderweg en Inkomend (P3b), echte mappen (P4) en sturen tussen twee andere apparaten (P9). Code: `crates/tandem-core/src/journal.rs`
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
| `requested_by` | het apparaat op wiens verzoek dit aanbod gemaakt is, als dat niet dit apparaat is (P9); anders leeg |

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
- **Naar schijf gaat elke verandering van stand en elk einde van een bestand** (dat laatste gebundeld, zie hieronder). Niet naar schijf: `running` en de bytes onderweg,
  die staan alleen in het geheugen (de engine werkt ze bij met de voortgang). Bij de start wordt alles wat liep `interrupted`, en
  voor binnenkomende bestanden komt `done` uit de lengte van het deelbestand: dat is waar een hervatting begint, en zo is er geen
  schrijfbeurt per stukje. Een binnenkomend aanbod dat al ja had en bestanden die nog op een vrije plek wachtten, worden ook
  `interrupted`: ze waren onderweg.
- **Geschreven terwijl het boek op slot zit**: twee wijzigingen van een aanbod komen nooit in de verkeerde volgorde op schijf, en
  een late schrijfbeurt schrijft wat nu in het geheugen staat, nooit een oudere kopie over een nieuwere of een verwijderd aanbod
  terug.
- **Het einde van een bestand wordt gebundeld (R-V12)**: een schrijfbeurt kost een fsync, en een aanbod van honderden kleine bestanden
  schreef er honderden. `Journal::file_ended` (het einde van `receive_item` en `served`) schrijft per aanbod hooguit elke halve seconde
  (`WRITE_EVERY`); een taakje schrijft de rest als de beurt komt. Meteen naar schijf gaan: een nieuw aanbod, elke verandering van
  `stage`, een stop, het laatste bestand van een aanbod, en alles na het begin van `Engine::shutdown` (`flush_all`). Een kill
  verliest dus hooguit de laatste halve seconde, en de start lost dat op zoals elke kill: een bestand dat wel binnenkwam maar niet als
  `done` op schijf stond wordt `interrupted` met `done` 0 (het deelbestand was al verplaatst), en een hervatting haalt het dan opnieuw
  op, zodat het twee keer in Downloads staat. Dat is de prijs van de bundeling en komt alleen na een kill voor.
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
geen reden geeft. Elk bestand krijgt zo'n `Finished` een keer: alleen de overgang naar de eindstand geeft er een.

Sinds P2 en P5 komen erbij (constanten in `transfer.rs`): `gone` (de afzender heeft het aanbod of het bestand niet meer),
`changed` (het bestand veranderde bij de afzender sinds het aanbod) en `no space` (de ontvanger heeft te weinig vrije ruimte). Een
afzender stuurt bij een pull van een aanbod dat hij niet heeft nog steeds de zin `that offer is no longer available`, zodat oudere
ontvangers iets leesbaars tonen; een nieuwe ontvanger maakt daar `gone` van. Een aanbod dat nog niet opgehaald werd en gestopt wordt
(door de afzender of de persoon) geeft bij de ontvanger nu ook een `Finished` met `cancelled` per bestand, zodat de app de vraag kan
weghalen.

### Besluiten

- **Een afzender die opnieuw start bedient zijn bewaarde aanbiedingen.** Een ontvanger die nog aan het ophalen was (die probeert
  het 10 minuten opnieuw) krijgt de rest gewoon. De regel van Mark, nooit vanzelf verder, gaat over de ontvanger: die haalt na een
  herstart pas weer op als de persoon ja zegt (P2). Op Android verloopt de toestemming voor een bestand uit een andere app na een
  herstart, dan antwoordt de afzender `could not open the file`; dat lost P3 op met de kopie in `outgoing/`.
- **Alles komt in het boek**, ook klembord, foto's en scans (`Capture`) en slepen (`Drag`). Zulke aanbiedingen horen bij een
  moment: P2 biedt ze niet aan om te hervatten maar gooit ze bij de start weg (`settle_after_restart`).
- **`accept_offer` start alleen een aanbod dat op een ja wacht.** Op een overdracht die halverwege stopte geeft het een fout: die
  gaat alleen verder via P2.
- **Een herhaald aanbod met hetzelfde nummer** van dezelfde afzender wordt overgeslagen; de nummers zijn willekeurig, dus het is
  hetzelfde aanbod twee keer.

## De pakketten die erop bouwen

De functies van P3 en verder zijn een voorstel voor `ffi.rs`. `Engine::transfers()` (alleen in Rust) geeft het
hele boek, het nieuwste eerst.

### P2. Hervatten na een herstart (gebouwd)

Na de start loopt er niets. De regel van Mark: nooit vanzelf verder. De kern meldt de halve overdrachten, de app vraagt "Verder
gaan of weggooien?", en pas een ja haalt de rest op.

- **Welke**: `Engine::resumable_transfers()` (ffi `resumable_transfers() -> Vec<TandemResumable>`) geeft elke binnenkomende
  overdracht met `stage: accepted`, minstens een bestand `interrupted`, die niet loopt en niet gestopt is, de nieuwste eerst. Geen
  gebeurtenis: wat de kern tijdens de start zou melden, kwam aan voor de app luisterde (`ffi.rs` abonneert pas na `Engine::start`).
  De app vraagt de lijst na de start en bij elke `Connected` (dan kan Verder gaan weer).
- **Ja**: `resume_transfer(from, offer)`. Een fout als de overdracht er niet op wacht of als de afzender niet verbonden is (de app
  toont Verder gaan dan grijs en zegt bij gebruik waarom; tien minuten wachten binnen een ja zou de persoon naar niets laten kijken).
  De kern vraagt de afzender met `ShareResume { id, items }` of hij de bestanden die nog moeten komen nog heeft zoals ze waren. De
  afzender antwoordt `ShareResumed { id, ok, changed, gone }`: `ok: false` als hij het aanbod voor die ontvanger niet meer heeft
  (verlopen, gestopt, of uit zijn boek verdwenen), anders per bestand of het veranderd is (andere grootte, of een andere
  `source_modified_ms` als die bekend was) of niet meer te openen. De afzender zet die bestanden voor die ontvanger op `failed` met
  de reden en zegt het zijn app met een `Finished`. Daarna haalt de ontvanger de rest op, elk bestand vanaf de lengte van zijn
  deelbestand (`pull_item` begint daar al; de controlesom leest het stuk dat er is opnieuw). Bestanden in een eindstand worden niet
  opnieuw opgehaald (`receive_all`).
- **Oude afzender**: alleen een apparaat met de cap `share.resume` (`transfer::RESUME_CAP`) wordt gevraagd. Een oudere afzender kent
  het bericht niet; de ontvanger haalt dan meteen op, en antwoordt die afzender `that offer is no longer available`, dan wordt dat
  `gone`. Komt er binnen 15 seconden geen antwoord, dan ook gewoon ophalen.
- **De afzender heeft het niet meer**: elk bestand dat nog moest komen eindigt met `Finished` en `gone`, de deelbestanden gaan weg en
  de overdracht is dicht.
- **Nee**: `discard_transfer(from, offer)`: deelbestanden weg, elk bestand dat nog moest komen eindigt met `Finished` en
  `cancelled`, en de afzender krijgt `ShareCancel` en sluit die ontvanger. Is de afzender nu weg, dan hoort hij het niet; zijn kant
  verloopt na `UNFINISHED_KEPT` (3 dagen), en er haalt intussen niemand meer op.
- **Van een moment** (`Clipboard`, `Capture`, `Drag`): bij de start zonder vraag gesloten als `cancelled`, deelbestanden weg. De
  afzender hoort het niet (bij de start is er geen verbinding); zijn kant verloopt na 3 dagen.

Tests: `tests/transfers.rs` (ja haalt de rest op vanaf het deelbestand en het bestand klopt; nee gooit weg en de afzender sluit;
de afzender heeft het niet meer; een veranderd bestand wordt gemeld en de rest komt) en `journal.rs` (een klembordaanbod wordt niet
aangeboden).

### P5. Vrije ruimte vooraf (gebouwd)

- **De ontvanger** vergelijkt wat nog moet komen (per bestand dat niet af is: grootte min wat er al is) met de vrije ruimte die
  zijn eigen app in de status meldt (`Status::free_storage`). Groter: geweigerd, voordat er iets opgehaald wordt. Dat gebeurt als
  het aanbod binnenkomt (dan wordt niemand iets gevraagd en komt er geen `ShareOffered`), bij een ja (de ruimte kan intussen op
  zijn) en bij Verder gaan van P2. Elk bestand eindigt met `Finished` en `no space`; de afzender krijgt `ShareReply` met reden
  `no space` en zet de bestanden op `failed` met een `Finished` per bestand. Een app die geen vrije ruimte meldt krijgt geen controle.
- **De afzender** vraagt vooraf `short_of_space(targets, files)`: de ontvangers die minder vrije ruimte melden dan de bestanden
  samen (een grootte van 0 wordt opgezocht zoals bij `send_files`). Niet leeg: vragen "toch sturen?" en pas dan `send_files`.

Test: `an_offer_larger_than_the_free_space_is_refused_up_front` in `tests/transfers.rs`.

### Wat de andere apps moeten doen (P2 en P5)

De Mac is het voorbeeld: `macos/Sources/Tandem/Model/HalfwayTransfers.swift` (de lijst, de antwoorden, de melding),
`macos/Sources/Tandem/UI/HalfwaySection.swift` (de rijen bovenaan Gedeeld), de redenen in `TransferRow`
(`macos/Sources/Tandem/UI/Components.swift`) en de vraag voor het sturen in `EngineModel.send(urls:to:)`. Er is geen nieuwe
`TandemEvent`, dus geen `when`/`match` breekt. Android gebruikt de Kotlin-bindings, de Linux- en Windows-app dezelfde functies van
`tandem_core::ffi::TandemEngine` in Rust.

**Na de start en bij elke `Connected`**: `resumable_transfers()` opnieuw lezen. Voor elke overdracht die er voor het eerst in staat
een melding (een keer per start), met twee knoppen. Op de pagina Gedeeld bovenaan, boven de lijst (ook als die leeg is, dan in plaats
van "Nothing yet"), een rij per overdracht: een rondje met een pauze-icoon, de naam van het eerste bestand dat niet af is (met "en
nog N"), een regel met hoe ver en van wie, een voortgangsbalk (grijs als de afzender weg is), en de knoppen Weggooien en Verder gaan.
Verder gaan is grijs zolang de afzender niet verbonden is en zegt bij een klik waarom (een toast), nooit een blijvende waarschuwing.
Na een antwoord de rij en de melding weg (met een animatie). De bestanden van een weggegooide overdracht krijgen geen eigen rij: de
app onthoudt welke ze weggooide en slaat hun `Finished` met `cancelled` over. Na Verder gaan komen de gewone `Progress` en `Finished`.

Velden van `TandemResumable`: `from`, `offer`, `origin`, `items` (`index`, `name`, `size`, `done`: de bytes die er zijn, `ended`: kwam
al heel binnen of eindigde, wordt niet opnieuw opgehaald), `updated_ms` (wanneer het stopte).

**De redenen** op een rij die eindigde (naast `cancelled`, `declined` en `expired`):

| Reden | Binnenkomend | Uitgaand |
| --- | --- | --- |
| `gone` | No longer on %@ / Niet meer op %@ | The file is no longer there / Het bestand is er niet meer |
| `changed` | Changed on %@ since it was sent / Veranderd op %@ sinds het gestuurd werd | Changed since it was sent / Veranderd sinds het gestuurd werd |
| `no space` | Not enough space on this device / Niet genoeg ruimte op dit apparaat (Mac: "this Mac" / "deze Mac") | Not enough space on %@ / Niet genoeg ruimte op %@ |

`%@` is de naam van het andere apparaat. Android: `AndroidFiles.finished` (`A/engine/AndroidFiles.kt`) laat een uitgaand bestand
nu alleen los bij `cancelled`, `declined` en `expired`; `gone`, `changed` en `no space` horen in die set, anders blijft de
toestemming voor het bestand hangen.

**Voor het sturen** (P5): `short_of_space(targets, files)` met dezelfde lijst als `send_files`. Niet leeg: een vraag met de namen,
"Cancel" en "Send anyway" (prominent); pas bij Send anyway `send_files`.

**Vrije ruimte melden**: de ontvanger weigert alleen als zijn eigen app `free_storage` in de status zet. Mac en Android doen dat
al. De Linux- en Windows-app nog niet (`windows/src-tauri/src` zet het nergens): die moet de vrije ruimte van de map Downloads
melden, net als de batterij in `power.rs`, anders weigert hij nooit vooraf en waarschuwt een afzender nooit voor hem.

**Teksten** (Engels / Nederlands):

| Waar | Engels | Nederlands |
| --- | --- | --- |
| melding, titel | A transfer from %@ stopped halfway | Een overdracht van %@ stopte halverwege |
| melding, tekst | %@. Go on or throw away? (naam van het bestand) | %@. Verder gaan of weggooien? |
| knoppen | Go on / Throw away | Verder gaan / Weggooien |
| rij, afzender verbonden | Stopped halfway, %1$@ of %2$@ from %3$@. Go on or throw away? | Halverwege gestopt, %1$@ van %2$@ van %3$@. Verder gaan of weggooien? |
| rij, afzender weg | Stopped halfway. %@ is not connected. | Halverwege gestopt. %@ is niet verbonden. |
| toast bij grijs Verder gaan | %@ is not connected. Open Tandem there to go on. | %@ is niet verbonden. Open Tandem daar om verder te gaan. |
| naam van meer bestanden | %1$@ and %2$d more | %1$@ en nog %2$d |
| zonder naam | the other device | het andere apparaat |
| vraag voor het sturen, titel | Not enough space on %@ | Niet genoeg ruimte op %@ |
| vraag voor het sturen, tekst | It has less free space than these files take. If they do not fit, it refuses them. | Daar is minder ruimte vrij dan deze bestanden innemen. Passen ze niet, dan weigert het ze. |
| knoppen | Cancel / Send anyway | Annuleer / Toch sturen |

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

### P9. Sturen tussen twee andere apparaten

Vanaf A zeg je tegen B: stuur dit naar C. B controleert dat A die bestanden mag lezen (`FilePolicy`, zoals bij Bestanden) en
dat C in de circle zit, en stuurt ze dan zelf naar C als gewoon aanbod. C beslist zoals altijd voor B. B geeft de voortgang door
aan A.

- Een bericht van A naar B, bijvoorbeeld `ShareOnBehalf { id, to, paths }` met de paden uit de mappen die B aan A toont. Een
  oude B kent het niet en slaat het over, dus A wacht met een tijdslimiet op antwoord en zegt dan dat B het niet kan (een cap
  `relay` voorkomt dat het geprobeerd wordt).
- B maakt een gewoon uitgaand aanbod met `requested_by: A` in het boek. Dat veld staat al in het formaat (P1), leeg voor alles
  wat iemand op B zelf stuurt.
- `ShareOffer` krijgt `requested_by` (`#[serde(default)]`), zodat C "op verzoek van A" kan tonen; C zet het ook in zijn boek. Een
  oude C leest het niet en ziet een gewoon aanbod van B.
- B stuurt A de voortgang en het einde per bestand (een klein bericht met aanbod, nummer, bytes en `TransferDone::error`), en
  A toont dat als uitgaand bij zichzelf met C als ontvanger. Stop op A gaat via B (`cancel_transfer` op B voor C).
- Na P6 vraagt B ook de poort `allowed(A, send_for)`; het paneel (P3b) toont het aanbod op alle drie.

Klaar als een kerntest met drie apparaten dekt: A laat B een bestand naar C sturen en het komt heel aan, A ziet de voortgang en
het einde; B weigert een pad dat A niet mag lezen en een C buiten de circle, met een reden bij A; C ziet `requested_by: A`; stop
op A stopt het bij C; een oude B (die het bericht niet kent) geeft A een nette melding.

## Na R-V7 voor de Android-app

De kern leest een bestand nu op positie (`read_at`), nooit via de leespositie van de descriptor. `AndroidFiles.openRead` hoeft
een kopie van een vastgehouden descriptor dus niet meer terug te zetten (`Os.lseek`), en twee apparaten kunnen hetzelfde bestand
tegelijk ophalen (R-B6).
