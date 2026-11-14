# Volgende fase

Alles wat nog niet af is, op volgorde van aanpak. Begin bovenaan. Een punt is pas af als het op echte
toestellen is gezien, niet alleen gecompileerd. Zie CLAUDE.md voor de werkwijze en de agentregels.

## Review van de hele code (2026-11-14, op main 26c8f3bd): voorstellen, en wat Mark koos (R-P)

Alleen gelezen, niets gebouwd of gedraaid. Elk punt heeft een vast nummer (R-B bugs en beveiliging, R-D slepen en neerzetten,
R-V verbeteringen, R-I ideeen, R-T testen, R-P gekozen en bouwplan), zodat een agent ernaar kan verwijzen. Per punt: waar,
wat er misgaat, hoe je het merkt, voorstel. Zekerheid: **zeker** (de code doet dit, gelezen van begin tot eind), **waarschijnlijk**, **vermoeden** (pas te
zien op een echt toestel). Binnen elke groep van ernstig naar klein. Een punt gaat eruit zodra het gebouwd en op een echt toestel
gezien is. Niet meegenomen: de hartslag van de verbindingen met het scherm uit en de vraag bij grote bestanden op mobiele data
(andere sessie). Niet gelezen: de horloge-app, de extensies van de Mac (camera, geluid, File Provider), de schermkijker en
encoders, sms, hotspot en Shizuku, Health Connect.

Korte paden: `K/` = `crates/tandem-core/src/`, `M/` = `macos/Sources/Tandem/`, `T/` = `windows/src-tauri/src/`,
`W/` = `windows/src-tauri/winsys/src/`, `A/` = `android/app/src/main/java/nl/markmaaktmedia/tandem/`.

**Stand 2026-11-14, avond.** Gebouwd en samengevoegd in main (PR 4, 5 en 6, changelog 0.3.17), en nog niet op een echt toestel
gezien behalve waar het erbij staat: R-B1, R-B2, R-B3, R-B4, R-B5 (gezien: de dev-app schrijft de sleutel), R-B6 met R-V7, R-B7,
R-B8, R-B9, R-B14, R-B15, R-B17, R-B20, R-B25 (zonder de interne acties van `MainActivity`), R-B27 (zonder de buffer in
`FsClient::read`), R-D1, R-D2, R-V6 (alleen apparaatnamen), R-V8, en P1, het overdrachtsboek (zie `docs/TRANSFERS.md`, daar staan
ook de lijsten "klaar als" voor P2, P3, P3b, P4 en P9). Die punten staan hieronder nog uitgeschreven tot ze op een toestel gezien
zijn. De redenen "declined" en "expired" die de kern nu geeft zijn in alle vier de apps vertaald.

Gevonden bij het nakijken van die PR's, nog te doen:

- **R-V12. Het overdrachtsboek schrijft na elk bestand naar schijf** (zeker, klein tot middel). `K/journal.rs` (`change`): een
  schrijfactie met `fsync` per bestand dat eindigt, aan beide kanten. Bij een aanbod van honderden bestanden is dat merkbaar, vooral
  op een telefoon. Voorstel: bundelen, hooguit elke halve seconde en altijd aan het eind.
- **R-B31. Windows: elk ontvangen bestand krijgt het merk "van internet"** (keuze voor Mark). `T/zone.rs`: ook een Office-bestand
  van je eigen telefoon opent daardoor eerst in beveiligde weergave. Zo laten, of alleen programma's, scripts en snelkoppelingen.
- **R-B32. Android: weiger je een aanbod in de app, dan blijft de melding tot 10 minuten staan** (klein).
  `A/engine/TandemService.kt` (`offerAnswered`) hoort alleen van voortgang en van klaar. Voorstel: ook aanroepen bij weigeren.
- **R-V13. De redenen van de kern zijn Engelse zinnen, in elke taal** (zeker, klein tot middel). Een overdracht die mislukt toont
  de tekst van de kern zoals hij is, bijvoorbeeld "The connection dropped" in een Nederlandse app (gezien in de proefversie van de
  Linux- en Windows-app, 2026-11-14). Alleen "cancelled", "declined" en "expired" zijn vertaald. Voorstel: de kern geeft een vaste
  code per reden (verbinding weg, controlesom, geen ruimte, bestand weg) en de apps vertalen die.
- **R-T:** de proef van R-B1 is gedaan op de Oppo (2026-11-14): voor de fix stuurde een deelverzoek van buiten zonder tik, erna
  niet meer. Wat Mark zelf nog moet zien staat in `docs/TESTLIJST.md`, die bij elke samengevoegde PR wordt bijgewerkt.

### R-P. Gekozen door Mark (2026-11-14), en hoe het gebouwd wordt

Mark koos negen functies: de wachtrij, neerzetten met een plaatshouder, de telefoon bij slepen, echte mappen, hervatten na een
herstart, vertrouwen per apparaat met een pagina Circle, vrije ruimte vooraf, een paneel Onderweg en Inkomend, en sturen tussen
twee andere apparaten. Niet nu: de plank aan de schermrand (R-I7). Geschrapt: de gedeelde klembordgeschiedenis (R-I10). Komt er
nooit: een pagina Regels (R-I30). De bugs (R-B) en de rest zijn nog niet gekozen.

Werkwijze, zodat een lichter model het meeste kan bouwen zonder dat de kwaliteit zakt:

1. **Het contract, door een sterk model**: het bericht in `proto.rs`, de logica en de tests in de kern, de functies in `ffi.rs`,
   en per pakket een korte lijst "klaar als". Hier zit wat moeilijk is: UniFFI, async, en oude versies die moeten blijven werken.
2. **De Mac als voorbeeld, door hetzelfde model**: een schil helemaal af, zodat de andere drie iets hebben om na te doen.
3. **De andere schillen, door een lichter model** (Sonnet, of de lokale AI met `/lokaal`): Android, dan Linux en Windows, een
   apparaat per taak, met als opdracht "doe na wat de Mac doet" en een test die vanzelf slaagt of faalt (bouwen, de kerntest,
   `mock.js`).
4. **Controle door het sterke model**: de diff lezen, zelf bouwen, de lijst "klaar als" nalopen. Daarna pas een echt toestel.

Regels: kernpakketten na elkaar (ze raken allemaal `proto.rs`, `ffi.rs` en `transfer.rs`), schillen mogen naast elkaar. Geen
pakket zonder test. Een licht model zegt niet zelf dat iets af is. Goed opwarmwerk voor het lichte model, elk een bestand met een
duidelijke oplossing: R-B4, R-B5, R-B7, R-B8, R-B17, R-B20, R-D1, R-D2. Eerst doen, want de pakketten bouwen erop: R-B8, R-V6, R-V7.

Aansturing (afgesproken met Mark, 2026-11-14): een sessie stuurt, en start per ronde hooguit drie bouwsessies die elkaars
bestanden niet raken, elk vers gestart, in een eigen werkmap (`.claude/worktrees/` in de repo, vanaf de nieuwste main) en met een
eigen PR. Kern en ontwerp op Opus (high), schillen en kleine fixes op Sonnet (medium). De sturende sessie leest elke PR na, voegt
hem samen als de CI groen is (rebase), werkt daarna dit bestand en `docs/TESTLIJST.md` bij, en start dan vanzelf de volgende ronde.
Meer sessies tegelijk maakt het niet sneller af: de limiet van het abonnement is de rem, niet het aantal sessies.

Wat elke bouwsessie meekrijgt: `AGENTS.md` en `CLAUDE.md` lezen en volgen; nooit in `/Users/mark/Documents/Apps/Tandem` zelf
werken; `docs/ROADMAP.md`, `docs/TESTLIJST.md` en `changelog.json` niet aanraken; de PR eindigt met "Te testen door Mark"; alleen
de debug-app op de Oppo (`3B164V0127K00000`), die gedeeld wordt, dus liever een eigen emulator; nooit de Xperia; eerlijk zeggen
wat een test bewijst, wat gezien is en wat alleen gecompileerd is.

Rondes:

- **Ronde 1 (klaar, PR 4 tot 8):** de zekere bugs, beveiliging, het delen op Android, en P1.
- **Ronde 2 (gestart 2026-11-14):** (a) kern en Mac: eerst een gewone overdracht op main controleren, dan R-V12, P2 en P5 met de
  Mac als voorbeeld; (b) Android: R-B19, R-B21, R-B25, R-B28, R-B32, R-V1, R-V3, R-V4; (c) Linux en Windows: R-B10, R-B18, R-B24,
  R-B29, R-D11, R-V5. Los daarvan: de updatebanner die de navigatiebalk verschuift.
- **Ronde 3 (start vanzelf na ronde 2):** (a) kern en Mac: P3 en P3b, en R-V13 (vaste codes voor redenen); (b) Android en Linux en
  Windows: de schillen van P2 en P5 naar het voorbeeld van de Mac, zoals `docs/TRANSFERS.md` het beschrijft; (c) Mac: R-B11,
  R-B16, R-B23, R-B26 en het slepen vanaf en naar de Mac (R-D5 tot R-D8, R-D12, R-D13, R-D16, R-D17, R-D18).
- **Ronde 4 (start vanzelf na ronde 3):** (a) kern en Mac: P4 en P9, en de kleine kernpunten R-B12, R-B13 en de rest van R-B27;
  (b) Android en Linux en Windows: de schillen van P3 en P3b, en op Android slepen in en uit de app (R-D4 a en b); (c) P6: de
  poort in de kern en een proef van de pagina Circle in `mock.js`, waarna Mark kiest.
- **Ronde 5 (wacht op Mark):** P7 en de rest van P8, de schillen van P4 en P9, en de sleeppunten voor Linux en Windows (R-D10,
  R-D14, R-D15). Alleen te beoordelen op echte computers: vraagt toegang tot de Linux-laptop en een Windows-pc. Ook wachtend op
  een keuze van Mark: het ontwerp van de pagina Circle, en R-B31.

Pakketten, in deze volgorde:

- **P1. Een overdrachtsboek in de kern** (**gebouwd, PR 6**; sterk model; basis voor P2, P3 en P4). Aanbiedingen, in en uit, bewaard in de datamap:
  wat, voor wie, hoe ver. Nu staan ze alleen in het geheugen (`K/engine.rs:117`). Klaar als: een kerntest een overdracht
  halverwege stopt, de engine opnieuw start en het boek de stand teruggeeft.
- **P2. Hervatten na een herstart** (R-I5). Eis van Mark: nooit vanzelf doorgaan. Na de start meldt de kern per halve overdracht
  dat hij er is; de app vraagt "Verder gaan of weggooien?". Pas na ja wordt de rest opgehaald; nee gooit het deelbestand weg en
  zegt het de afzender. Vraagt een bericht "dit aanbod opnieuw", want de afzender moet het bestand nog hebben. Klaar als: de
  kerntest het ja-pad, het nee-pad en "de afzender heeft het niet meer" dekt.
- **P3. Wachtrij voor apparaten die weg zijn** (R-I4). `send_files` zet ontvangers die offline zijn in het boek; bij `Connected`
  gaat het aanbod uit. Te zien bij Gedeeld als "wacht op <apparaat>", met een kruisje. Grenzen: vervalt na 7 dagen; is het bestand
  weg of veranderd, dan melden en niet sturen. Android: een bestand van een andere app eerst naar `outgoing/` kopieren, want de
  toestemming verloopt.
- **P3b. Een paneel Onderweg en Inkomend** (R-I21, gekozen 2026-11-14). Een plek voor alles wat loopt, binnenkomt, op een ja
  wacht, in de wachtrij staat of mislukt is, met Opnieuw en Stop, en bij een halve overdracht Verder gaan of Weggooien. Leest het
  overdrachtsboek van P1, dus na P2 en P3. Op alle vier de apps, de Mac eerst als voorbeeld.
- **P4. Echte mappen** (R-I2). `ShareItem` krijgt een relatief pad (`#[serde(default)]`), de ontvanger maakt de mappen, en een
  oude versie krijgt de zip zoals nu (nieuwe cap). Elk deel van het pad door de naamcontrole (R-V6). Klaar als: een kerntest met
  drie lagen, een lege map en een naam die naar buiten wil.
- **P5. Vrije ruimte vooraf** (R-V9; klein, een goed eerste pakket voor het lichte model). De afzender waarschuwt als het aanbod
  groter is dan de vrije opslag die de ontvanger meldt. De ontvanger weigert met de reden "geen ruimte" voor het ophalen begint.
- **P6. Vertrouwen per apparaat en een pagina Circle** (R-I6). Kern: per apparaat een niveau (eigen, beperkt, alleen sturen) en een
  poort `allowed(peer, action)` in `K/session.rs` voor elke gevoelige gebeurtenis: bestanden wissen, ontgrendelen, vergrendelen,
  bedienen, wifi-wachtwoord, bellen, meldingen, klembord, herstarten. In de kern, zodat alle vier de apps het vanzelf hebben. Elk
  apparaat meldt zijn tabel in de status, zodat elk apparaat het hele web kan tekenen. De pagina: de apparaten in een kring met
  lijnen ertussen, een kleur per soort recht; tik op een apparaat en het komt in het midden, met een spaak naar elk ander apparaat
  en de schakelaars erop. Wat een ander apparaat toestaat verander je daar ("Open op <apparaat>" bestaat al). Eerst een ontwerp
  door het sterke model, met een proef in `mock.js`, en Mark kiest.
- **P7. Neerzetten met een plaatshouder** (R-I1, groot). Mac eerst (`NSFilePromiseProvider`), dan Windows, dan Linux. Alleen op
  echte computers te beoordelen; niet voor een licht model.
- **P8. De telefoon doet mee met slepen** (R-I3, R-D4). Stap (a) en (b) zijn afgebakend en geschikt voor het lichte model; stap
  (c), het kaartje bij de aanwijzer, na P7. Zie ook R-I16.
- **P9. Sturen tussen twee andere apparaten** (R-I22, gekozen 2026-11-14). Vanaf apparaat A zeg je tegen B: stuur dit naar C.
  Kern: een bericht van A naar B met de paden (uit de mappen die B aan A toont) en het doel. B controleert dat A die bestanden mag
  lezen (`FilePolicy`) en dat C in de circle zit, en stuurt ze dan zelf naar C als gewoon aanbod, met "op verzoek van A" erbij. C
  beslist zoals altijd voor B. B geeft de voortgang door aan A. In de apps: bij Bestanden van een ander apparaat een knop "Stuur
  naar" met de andere apparaten. Na P6, zodat de poort van het vertrouwen er is; het paneel (P3b) toont hem.

### R-B. Bugs en beveiliging

Ernstig:

- **R-B1. Android: elke app op de telefoon kan Tandem zonder tik laten sturen** (zeker). `A/share/ShareTargetActivity.kt:81`
  vertrouwt `EXTRA_SHORTCUT_ID` van elke app (ook "all") en stuurt meteen (`:113`). `A/engine/EngineHost.kt:440` opent daarbij
  elk adres, ook `file://` naar de eigen map van Tandem (de ssh-sleutel van de telefoon, `A/ssh/SshSession.kt:128`). Tekst die
  met http begint gaat mee als "openen" (`:142`) en opent vanzelf in de browser van elke computer (`M/Model/EngineModel.swift:553`).
  Merk je: tabbladen die vanzelf opengaan, of bestanden op je computers die je nooit stuurde. Voorstel: `file` en de eigen
  providers weigeren, de snelkoppeling alleen volgen als het deelmenu van Android het venster startte, en gedeelde tekst nooit
  met "openen" sturen zonder tik.
- **R-B2. Een apparaatnaam breekt "Sturen naar", en kan opdrachten uitvoeren** (zeker dat het breekt, misbruik waarschijnlijk).
  Windows `T/sendwith.rs:396`: alleen de rechte apostrof wordt verdubbeld, maar PowerShell leest de gekrulde ook als aanhalingsteken,
  en macOS noemt een Mac standaard "Mark’s MacBook Pro". Het script mislukt dan en er komt voor geen enkel apparaat een
  snelkoppeling. Linux `T/sendwith.rs:314` en `:323`: de naam gaat rauw in het bestand van Dolphin en Nemo; een naam met een
  regeleinde krijgt zo een eigen `Exec`. Voorstel: de snelkoppeling in Rust maken (IShellLinkW), en namen een keer schoonmaken in
  de kern (R-V6).
- **R-B3. Ontvangen bestanden krijgen geen quarantainemerk** (zeker dat het ontbreekt). Nergens wordt `com.apple.quarantine`
  (Mac) of `Zone.Identifier` (Windows) gezet. `M/Model/FilesBrowser.swift:244` haalt een bestand van een ander apparaat op en
  opent het meteen; `T/commands.rs:648` en `T/desktopsearch.rs:638` ook. Een `foto.command` of `.lnk` start zo zonder de vraag
  van Gatekeeper of SmartScreen. Voorstel: het merk zetten op alles wat binnenkomt, en eerst vragen bij uitvoerbare soorten.
- **R-B4. Android 12 en 12L: crash bij de eerste melding die gespiegeld wordt** (zeker). `A/mirror/MirrorListener.kt:142`, `:155`,
  `:201`, `:261` gebruiken `getParcelable(naam, klasse)`, dat pas vanaf Android 13 bestaat; `minSdk` is 31 en lint breekt de bouw
  niet af (`android/app/build.gradle.kts:102`). Voorstel: `BundleCompat.getParcelable`, en R-V8.
- **R-B5. Mac: de knoppen in het Controlecentrum doen niets** (zeker). `M/Platform/ControlBridge.swift:50`: `current()` zet de
  sleutel niet in `control-state.json`, de extensie stuurt dus geen sleutel mee (`macos/ControlExtension/Controls.swift:33`) en
  `perform` stopt op regel 74. Voorstel: `key: key` meegeven. Bevestigd op de echte Mac (2026-11-14): `control-state.json` van
  de draaiende app (0.3.15) heeft geen `key`.
- **R-B6. Android: een bestand naar twee apparaten tegelijk mislukt** (zeker). `A/engine/AndroidFiles.kt:28`: elke lezer krijgt een
  kopie van dezelfde descriptor, en kopieen delen een leespositie; de `lseek(0)` van de een zet de ander terug. De controlesom
  klopt niet, het wordt opnieuw geprobeerd en mislukt weer. Voorstel: in de kern op positie lezen (R-V7).
- **R-B7. Android: meer dan 48 bestanden delen verliest er een paar** (zeker). `A/engine/AndroidFiles.kt:75`: bij 48 wordt een
  willekeurige descriptor gesloten, ook van een bestand dat nog niet is opgehaald. `release()` wordt nergens aangeroepen, dus de
  rest blijft voor altijd open. Voorstel: volgorde van binnenkomst, een hogere grens, en sluiten als het laatste apparaat klaar is.
- **R-B8. Stoppen naar een apparaat stopt het sturen naar alle apparaten** (zeker). `K/transfer.rs:134`: `cancel_transfer` zet het
  stopteken van het hele aanbod (`on_share_cancel` op regel 146 doet het wel goed). Voorstel: alleen stoppen als er geen
  ontvangers meer zijn.

Middel:

- **R-B9. Een naam uit de bestandenlijst van een ander apparaat kan buiten de map schrijven** (waarschijnlijk).
  `M/Model/FilesBrowser.swift:240`, `:254`, `:270` en `T/browse.rs:90` plakken de naam zo aan de doelmap; `K/files.rs` controleert
  alleen aan de kant die bedient. Vraagt een apparaat in de circle dat liegt. Voorstel: namen met `/`, `\`, `..` of een schijfletter
  weigeren en na het samenstellen controleren dat het doel in de map ligt.
- **R-B10. Updater op Linux en Windows: de download staat op een voorspelbare plek** (waarschijnlijk, alleen met een tweede
  gebruiker op de pc). `T/update.rs:341`: `/tmp/Tandem-update`. De controle gebeurt in het geheugen, daarna wordt het bestand
  opnieuw van dat pad gekopieerd (`:325`). Voorstel: een eigen map met rechten 0700 en de gecontroleerde bytes zelf wegschrijven.
- **R-B11. Updater op de Mac** (zeker in de code). `M/Platform/Updater.swift:239`: ontbreekt de sleutel of is hij geen base64, dan
  wordt de handtekening overgeslagen in plaats van geweigerd. Geen controle dat de uitgepakte versie bij de tag hoort (een oude,
  getekende build kan als nieuw worden aangeboden). `:102`: `check()` let niet op een lopende download, dus weer actief worden na
  een minuut start een tweede. `:230` en `:280` draaien op de hoofddraad. Voorstel: dicht falen, versie vergelijken, vroeg stoppen
  tijdens downloaden, en het uitpakken van de hoofddraad af.
- **R-B12. Hervatten bij Bestanden kan stil een kapot bestand opleveren** (zeker). `K/files.rs:888`: het plakt verder aan
  `<naam>.tandem-part` zonder te kijken of de bron nog dezelfde is, en zonder controlesom. Voorstel: grootte en `modified_ms` van
  de eerste poging bewaren, bij verschil opnieuw beginnen, aan het eind de lengte controleren.
- **R-B13. Quick Share laat halve bestanden staan en begrenst niets** (zeker). `K/quickshare/transfer.rs:261`: bestanden krijgen
  meteen hun echte naam en worden bij een fout of annuleren niet weggehaald. `:273`: er wordt niet gekeken of er meer binnenkomt
  dan aangekondigd. `K/quickshare/service.rs:248`: onbeperkt verbindingen tegelijk, zonder tijdslimiet op de begroeting. Voorstel:
  als `.part` schrijven en aan het eind hernoemen, stoppen boven de aangekondigde grootte, hooguit 8 tegelijk en 15 seconden.
- **R-B14. Een geweigerd aanbod: de afzender hoort niets** (zeker). `K/session.rs:443`: geen gebeurtenis. Het bestand blijft op
  wachten staan tot het na 6 uur stil verdwijnt (`K/engine.rs:1237`). Voorstel: per bestand een `Finished` met "geweigerd", ook
  als een aanbod verloopt.
- **R-B15. Android: delen van veel bestanden of bestanden uit de cloud bevriest de app** (zeker dat het op de hoofddraad draait).
  `A/engine/EngineHost.kt:413` en `:440`: `describe` opent elk bestand op de hoofddraad; bij Google Foto's haalt dat het bestand
  eerst op. Voorstel: `withContext(Dispatchers.IO)`.
- **R-B16. Mac: een map sturen bevriest het venster** (zeker). `M/Model/EngineModel.swift:1224` zipt op de hoofddraad
  (`M/Platform/Files.swift:95`), de tijdelijke zip wordt nooit weggehaald, en `outgoingSources` (`:1226`) is op naam: twee
  bestanden met dezelfde naam wijzen naar de verkeerde bron. Voorstel: zippen buiten de hoofddraad, opruimen na `Finished`,
  sleutel op aanbod en nummer.
- **R-B17. Android: het deelvenster** (`A/share/ShareTargetActivity.kt`). Stuurt dubbel of breekt af bij draaien of vouwen tijdens
  het wachten (`:113`, waarschijnlijk). Tekst met opmaak wordt geweigerd met "niets om te delen" (`:100`, `getStringExtra`, zeker).
  Een foto met bijschrift overschrijft het klembord van de computer (`:140`, zeker). `A/ui/screens/DevicesScreen.kt:117`: gekozen
  bestanden vallen weg als het scherm draait terwijl de kiezer open is (`remember`, zeker). Voorstel: sturen in `graph.scope`,
  `getCharSequenceExtra`, tekst alleen zonder bestanden, `rememberSaveable`.
- **R-B18. De pagina Bestanden op Linux en Windows is niet af** (zeker). `T/browse.rs:76`: mappen worden overgeslagen, er is geen
  upload en een bestand openen kan niet; de Mac kan alles. `T/commands.rs:346`: een map die je sleept of kiest om te sturen
  wordt stil overgeslagen. Voorstel: overnemen van `FilesBrowser.swift`, met de naamcontrole van R-B9.
- **R-B19. Android: een link die met "openen" komt kan verdwijnen** (waarschijnlijk). `A/engine/EngineHost.kt:320`: starten vanuit
  de achtergrond wordt door Android stil geweigerd, en dan staat de link ook niet op het klembord. Voorstel: dan een melding met
  de link en het klembord vullen.
- **R-B20. Android: meldingen die blijven hangen**. "Ontvangen 45%" na een crash (`A/engine/TandemService.kt:385`, waarschijnlijk)
  en Aannemen of Weigeren van een aanbod dat al weg is (`:476`, zeker). Voorstel: wegnemen in `onCreate`, `setTimeoutAfter`.
- **R-B21. Android: de aanwijzer van de gedeelde muis en de tik lopen onderaan uiteen** (waarschijnlijk). `A/live/PointerIn.kt:61`
  gebruikt maten zonder de systeembalken, `A/live/RemoteInput.kt:32` de echte. Voorstel: dezelfde `getRealMetrics`.

Klein, of bewust zo en ter overweging:

- **R-B22. Wat elk apparaat in de circle zonder vraag mag** (bewust, ter overweging; zie R-I6). Bestanden lezen, wijzigen en
  wissen, op Android standaard de hele opslag (`K/files.rs:75`, `:187`). Het wifi-wachtwoord opvragen op Linux en Windows
  (`T/wifi.rs:247`). Tandem op de Mac herstarten (`M/Platform/FeatureReport.swift:142`). Alle meldingen en inlogcodes krijgen,
  wat de schakelaar per apparaat ook zegt (`A/mirror/MirrorListener.kt:79`). Bestanden neerzetten met herkomst `Drag` zonder dat
  de muis er is, alleen op de Mac (`M/Platform/DragLanding.swift:15`, Linux en Windows eisen de gedeelde muis: `T/drag.rs:34`).
  Met een gestolen of vergeten laptop in de circle is dat veel.
- **R-B23. `tandem://send` zonder sleutel** (waarschijnlijk). `M/Model/EngineModel.swift:1451`: een webpagina kan een keuzemenu
  van apparaten laten opkomen voor een pad naar keuze, en het menu toont niet wat er gestuurd wordt (`M/App/TandemApp.swift:160`).
  Voorstel: zulke links zonder sleutel weigeren, of de bestandsnamen tonen.
- **R-B24. Linux: tekst van een melding wordt als opmaak gelezen** (waarschijnlijk). `W/notify.rs:81`: tekst uit een sms of van een
  vreemde via Quick Share gaat rauw naar de meldingsdienst; `a < b` verdwijnt, en een link kan erin. Voorstel: `&<>` vervangen.
- **R-B25. Android, van buitenaf bereikbaar** (klein). `A/engine/BootReceiver.kt:12` kijkt niet welke actie het is (vermoeden:
  een andere app kan Tandem laten crashen). `A/MainActivity.kt:28`: interne acties werken voor elke app. Een Quick Share-link van
  een vreemde opent elk schema (`A/quickshare/QuickShareNotifications.kt:104`), ook `tandem://pair`. Voorstel: actie controleren,
  interne acties via een alias die niet naar buiten staat, alleen http en https.
- **R-B26. Mac: de sudo-regel voor wakker blijven** (waarschijnlijk, klein). `M/Platform/KeepAwake.swift:202`: `visudo` controleert
  een bestand van de gebruiker en `install` kopieert daarna datzelfde pad als root. Voorstel: de regel in het script zelf schrijven.
- **R-B27. Kern, klein** (zeker). `K/files.rs:880`: de buffer is zo groot als het antwoord zegt, niet als gevraagd (weigeren als
  `sent > len`). `K/transfer.rs:297`, `:520`: een half bestand blijft drie dagen staan en kan na een herstart nooit afgemaakt
  worden (bij de start weggooien, of R-I5). `K/transfer.rs:120`: een aanbod dat op een ja wacht verloopt nooit. `K/store.rs:37`:
  het tijdelijke bestand heeft een vaste naam (vermoeden: twee schrijvers tegelijk).
- **R-B28. Android, klein**. Mislukt het opslaan, dan blijft de kopie drie dagen staan (`A/engine/AndroidFiles.kt:47`, zeker). Een
  bestand zonder naam komt zonder extensie aan (`A/engine/EngineHost.kt:442`, zeker). Gebeurtenissen kunnen wegvallen als de app
  achterloopt (`:182`, vermoeden).
- **R-B29. Linux en Windows, klein**. `T/sendwith.rs:26`: `--send --to <id>` stuurt zonder vraag voor elk lokaal programma (de Mac
  eist een sleutel). `T/commands.rs:648`: `open_path` neemt elk pad van elk venster. 428 keer `lock().unwrap()`: een paniek met
  een slot vast legt daarna alles plat (vermoeden).
- **R-B30. De gastinstelling is dode code** (zeker). `K/store.rs:131` zegt dat hij niet meer gebruikt wordt, maar `K/engine.rs:327`,
  `K/ffi.rs:1834`, `M/Model/EngineModel.swift:1352` en `T/commands.rs:207` bestaan nog, zonder knop. De tekst "Gasten en familie"
  verderop zegt nog dat hij gebouwd is. Voorstel: weghalen en die tekst bijwerken.

### R-D. Slepen en neerzetten

Wat er nu is, per richting:

| Van, naar | Over de rand met de gedeelde muis | In het venster van de app |
| --- | --- | --- |
| Mac naar Mac, Linux, Windows | ja: bestand, plaatje, tekst (Mac als hoofd) | laten vallen op een apparaat: ja |
| Linux of Windows naar Mac | ja, alleen bestanden, alleen als bediende computer | n.v.t. |
| Linux of Windows naar buiten | alleen als bediende computer, niet met de eigen muis | laten vallen: gaat altijd naar het gekozen apparaat (R-D11) |
| naar Android | nee: het bestand komt in Downloads met een gewone melding | n.v.t. |
| vanaf Android | nee | geen drop van een andere app in Tandem |
| uit Tandem slepen (ontvangen of bestand op afstand) | n.v.t. | nergens |

- **R-D1. Linux: een gesleept bestand heet daarna "naam 2.ext"** (zeker). `T/drag.rs:198` (`place`): het bestand staat al in de
  downloadmap (`T/engine.rs:68`), en `land` (`T/drag.rs:167`) verplaatst het naar diezelfde map, ziet dat de naam bezet is (door
  zichzelf) en telt door. Hetzelfde op de Mac als het voorste Finder-venster de downloadmap is (`M/Platform/DragLanding.swift:57`).
  Voorstel: niets doen als bron en doel dezelfde map zijn.
- **R-D2. Windows: van een sleep met meer bestanden komt alleen het eerste onder de aanwijzer** (zeker). `T/drag.rs:82` start een
  systeemsleep per bestand dat binnen is; `W/dragsource.rs:48` weigert een tweede zolang de eerste loopt, de rest gaat naar het
  bureaublad. Voorstel: wachten tot alle bestanden van het aanbod binnen zijn (het aantal staat in `ShareOffered`).
- **R-D3. Een groot bestand kun je pas neerzetten als het helemaal binnen is** (zeker, ontwerp). Mac `M/Platform/DragLanding.swift:28`
  en `DragCarrier.swift:30`, Windows `T/drag.rs:75`, Linux `T/drag.rs:100`. Merk je: je laat los op het andere scherm en er
  gebeurt niets; seconden later hangt er ineens een icoon onder de aanwijzer of ligt het bestand op het bureaublad. Voorstel
  (groot, R-I1): bij het oversteken meteen een plaatshouder onder de aanwijzer met voortgang (namen en groottes staan al in het
  aanbod), en neerzetten met een belofte: Mac `NSFilePromiseProvider`, Windows `CFSTR_FILEDESCRIPTOR` met `FILECONTENTS`, Linux
  XDS of het kaartje met voortgang.
- **R-D4. Android doet niet mee** (zeker). Nergens `dragAndDropTarget`, `dragAndDropSource` of `startDragAndDrop`. Een bestand dat
  met de gedeelde muis naar de telefoon gesleept wordt, wordt wel zonder vraag aangenomen (`A/engine/TandemService.kt:179`) maar
  komt in Downloads met een gewone melding, los van waar je losliet; `A/live/PointerIn.kt` zet de knop bij binnenkomst op los,
  dus het loslaten doet niets. Is de muis al terug voor het aanbod verwerkt is, dan komt er toch een vraag. Voorstel in stappen:
  (a) een drop van een andere app op een apparaatkaart (`dragAndDropTarget` met `requestDragAndDropPermissions`), (b) ontvangen
  bestanden uit Tandem slepen (`DRAG_FLAG_GLOBAL` met leesrecht), (c) bij de gedeelde muis een kaartje naast de aanwijzer in de
  overlay, met Openen, Delen en Tonen, en plakken in het veld onder de aanwijzer via de toegankelijkheidsdienst.
- **R-D5. Mac: slepen uit Foto's, Mail, Berichten, Notities of een browser gaat niet of slecht mee** (waarschijnlijk, te zien op
  de Mac). `M/Platform/PointerShare.swift:471` (`dragContents`) leest alleen echte bestandsadressen, dan tekst, dan een plaatje.
  Apps die een bestand beloven vallen erbuiten: een video of pdf gaat niet mee, een foto wordt een nieuwe png zonder naam, datum
  of origineel. Voorstel: `NSFilePromiseReceiver` lezen en de beloofde bestanden eerst in een eigen map laten zetten.
- **R-D6. Mac: een plaatje uit een browser gaat als webadres in tekst mee** (zeker in de code). Zelfde functie, regel 476: tekst
  wordt voor het plaatje geprobeerd. Voorstel: volgorde bestand, belofte, plaatje, tekst; of beide sturen en de ontvanger laten
  kiezen naar waar het losgelaten wordt.
- **R-D7. Mac: een bestand dat laat aankomt neemt de aanwijzer over** (zeker in de code). `M/Platform/DragCarrier.swift:99`: het
  paneeltje ligt tot 30 seconden precies onder de aanwijzer en vangt de volgende klik en het scrollen; een gewone klik zet de
  bestanden weg en haalt Finder naar voren (`DragLanding.swift:52`). Voorstel: alleen onder de aanwijzer hangen als het bestand
  binnen 2 seconden na het oversteken binnen is of de knop nog vast is; anders een kaartje naast de aanwijzer zoals op Linux.
  Scrollen altijd doorlaten.
- **R-D8. Mac: bestanden die na het begin van de sleep binnenkomen gaan naar het bureaublad** (zeker). `DragCarrier.swift:31`
  (`!dragging`) en `DragLanding.swift:36`. Merk je: van vijf foto's landen er twee waar je losliet. Voorstel: zie R-D2 of R-D3.
- **R-D9. Na elke sleep blijft een kopie in Downloads achter** (zeker). Het bestand komt eerst in de downloadmap
  (`M/Platform/Files.swift:15`, `T/engine.rs:68`) en de systeemsleep kopieert daarna (`DragCarrier.swift:147`, `W/dragsource.rs:174`).
  Voorstel: bestanden met herkomst `Drag` in een eigen map in de datamap ontvangen (zoals `clipboard`), verplaatsen toestaan, en
  die map na een dag opruimen.
- **R-D10. Een map slepen** (zeker). Vanaf Linux en Windows wordt een map stil overgeslagen (`T/edge.rs:79`); met alleen mappen
  gebeurt er niets. Vanaf de Mac wordt het een zip die een zip blijft (`M/Platform/Files.swift:86`). Voorstel: "Mappen versturen"
  (staat hieronder al) in de kern bouwen, met relatieve paden per bestand.
- **R-D11. Linux en Windows: een drop in het venster gaat altijd naar het gekozen apparaat** (zeker). `windows/ui/js/main.js:266`:
  laat je los op een ander apparaat in de zijbalk, dan gaat het toch naar het geselecteerde; op de pagina Bestanden wordt het
  gestuurd in plaats van in de open map gezet; is het apparaat offline, dan gebeurt er niets en zegt de app niets. Het trayvenster
  neemt geen drop. Voorstel: het doel bepalen uit de plek van de drop (`elementFromPoint` met `data-device-id`), een toast bij
  offline, een drop per rij in het paneel.
- **R-D12. Mac: een drop in het venster** (`M/UI/DeviceDetail.swift:602`, `MenuBarPanel.swift:405`). Een weblink uit de browser
  wordt als bestand behandeld en geeft een vreemde fout (zeker). Beloofde bestanden uit Foto's, Mail en Safari worden geweigerd
  (waarschijnlijk). Het Dock-icoon neemt geen bestanden (geen `CFBundleDocumentTypes`) en het menubalkicoon ook niet. Voorstel:
  weblinks en tekst als `shareText` sturen, beloften ontvangen, bestandssoorten aanmelden voor het Dock, en het paneel openen als
  je met een sleep boven het menubalkicoon hangt.
- **R-D13. Uit Tandem slepen kan nergens** (zeker). Geen `onDrag` of `draggable` op de Mac, niets in het Tauri-venster, niets op
  Android. Voorstel: ontvangen bestanden in de lijst sleepbaar maken (Mac `.draggable`, Windows met `W/dragsource.rs`, Android
  zie R-D4), en bestanden op afstand met een belofte (zie R-D3).
- **R-D14. Windows: de strook aan de rand staat alleen op het hoofdscherm, en is een heel webvenster** (zeker). `T/edge.rs:99`
  gebruikt `primary_monitor`; hij wordt bij elke binnenkomst van de aanwijzer opnieuw als WebView gemaakt (`:101`). Voorstel:
  een gewoon Win32-venster in `winsys` met `IDropTarget`, op de rand van het scherm waar de buur zit.
- **R-D15. Tekst en plaatjes slepen over de rand kan alleen vanaf de Mac** (zeker). Linux leest alleen `text/uri-list`
  (`W/edgecatch.rs:117`), Windows alleen paden (`T/edge.rs:118`). Voorstel: ook `text/plain` en `image/png` lezen.
- **R-D16. Mac: de map van het voorste Finder-venster wordt buiten de hoofddraad met AppleScript gevraagd** (waarschijnlijk).
  `M/Platform/DragLanding.swift:48`, `:77`: `NSAppleScript` hoort op de hoofddraad, en de eerste keer komt midden in een sleep de
  vraag of Tandem Finder mag bedienen. Voorstel: `NSUserAppleScriptTask` met een tijdslimiet, toestemming vooraf via "Wat werkt".
- **R-D17. Mac: de muis van de hele Mac hangt aan de hoofddraad van Tandem** (waarschijnlijk, eerst meten). De gebeurtenistap zit
  op de hoofdlus (`M/Platform/PointerShare.swift:321`) en leest in de callback het sleepklembord en maakt er zo nodig een png van
  (`:382`, `:477`). Voorstel: de tap op een eigen draad, de inhoud van een sleep een keer lezen bij het oversteken.
- **R-D18. Mac: mislukt het sturen, dan is de sleep al afgebroken** (zeker, klein). `PointerShare.swift:447`: Escape en loslaten
  gaan meteen, het sturen volgt 250 ms later (`:431`). Voorstel: pas afbreken als het aanbod verstuurd is, of de wachtrij (R-I4).
- **R-D19. Klein**: de map `Tandem drag` met gesleepte plaatjes wordt nooit opgeruimd en het plaatje wordt twee keer geschreven
  (`PointerShare.swift:382`, `:417`, `:478`); de strook op Linux maakt bij elke klik een nieuwe verbinding met de X-server
  (`T/edge.rs:59`); Linux en Windows als hoofdcomputer kunnen nog niet slepen met hun eigen muis (staat al verderop).

### R-V. Verbeteringen

Accu en data:

- **R-V1. Android stuurt ongeveer elke minuut een statusbericht, ook met het scherm uit** (zeker in de code, kosten waarschijnlijk).
  `A/engine/StatusReporter.kt:189`: de vrije opslag gaat in exacte bytes mee en is dus bijna altijd anders (`:140`). Voorstel:
  afronden op 1 GB, of opslag hooguit elk kwartier. Doorgeven aan de sessie die aan zuinigheid werkt.
- **R-V2. Android: elk ontvangen bestand kost twee keer zijn grootte en wordt twee keer geschreven** (zeker).
  `A/engine/AndroidFiles.kt:47`: eerst naar de eigen opslag, dan een kopie naar Downloads. Voorstel: de kern rechtstreeks in een
  wachtend MediaStore-bestand laten schrijven (`openDownload(naam, soort, grootte)` geeft een descriptor terug).
- **R-V3. Android: een grote overdracht kan stilvallen met het scherm uit** (waarschijnlijk). Er wordt geen wakelock of wifilock
  vastgehouden tijdens een overdracht (`A/engine/TandemService.kt`). Voorstel: beide, met een tijdslimiet, zolang er iets loopt.
- **R-V4. Bluetooth de hele dag op de snelle stand** (waarschijnlijk). De telefoon adverteert voor het horloge op `BALANCED`
  zolang het horloge weg is (`A/watch/PhoneBleServer.kt:150`); de Mac scant met dubbelen aan zolang een telefoon offline is
  (`M/Platform/BleMessenger.swift:152`). Voorstel: na een minuut `LOW_POWER`; scannen in blokjes en afbouwen.
- **R-V5. Timers en rondvragen zonder einde** (zeker, klein). Mac: `M/Platform/ControlBridge.swift:34` elke 2 s en
  `BatteryBridge.swift:27` elke 5 s. Linux: `T/lid.rs:46` elke seconde, `T/unlock.rs:33` elke 2 s. Kern: `K/engine.rs:985` vraagt
  elke 5 s de netwerkadressen op. Voorstel: op gebeurtenissen (logind `PropertiesChanged`, apparaten veranderd) in plaats van de klok.

Simpeler en beter:

- **R-V6. Namen een keer schoonmaken in de kern.** Apparaatnamen bij hernoemen en koppelen (`K/circle.rs:204`, `:234`): geen
  stuurtekens, een maximale lengte; dat beschermt elke app en R-B2 in een keer. Bestandsnamen: nu zes eigen versies
  (`K/transfer.rs:589`, `K/quickshare/transfer.rs:161`, `M/Platform/Files.swift:30`, `T/engine.rs:71`, `T/browse.rs:40`,
  `T/drag.rs:198`), elk met andere gaten. Een functie voor "veilige naam" en een voor "vrije naam in deze map".
- **R-V7. In de kern op positie lezen** (`read_at`) bij het bedienen van een bestand (`K/transfer.rs:447`). Lost R-B6 op en maakt
  het vasthouden van descriptors op Android eenvoudiger.
- **R-V8. Lint laat de bouw niet falen** (`android/app/build.gradle.kts:102`). Voorstel: `NewApi` als fout, dan was R-B4 gevonden.
- **R-V9. Vrije ruimte vooraf controleren.** Elk apparaat meldt zijn vrije opslag al in de status; de afzender kan vooraf zeggen
  dat het niet past in plaats van halverwege te stranden (`K/transfer.rs:77`, `:303`).
- **R-V10. Dubbele logica naar de kern.** De toestand van een wifi-aanbod staat in `M/Platform/WifiShare.swift` en `T/wifi.rs`;
  de updater (release lezen, versies vergelijken) in Swift en Rust.
- **R-V11. Grote bestanden splitsen**: `K/ffi.rs` (3556 regels), `M/Model/EngineModel.swift` (1709), `M/UI/HealthSettings.swift`
  (1600), `windows/ui/js/pages.js` (1467). Minder botsingen tussen agents.

### R-I. Ideeen voor nieuwe functies

- **R-I1. Neerzetten met een plaatshouder** (groot). Bij het oversteken hangt er meteen iets onder de aanwijzer, met voortgang; je
  laat los waar je wilt en het bestand komt daar zodra het binnen is. Lost R-D3, R-D7 en R-D8 op.
- **R-I2. Echte mappen sturen** (middel). Staat al op de lijst; ook de basis voor slepen (R-D10) en de pagina Bestanden (R-B18).
- **R-I3. De telefoon doet mee met slepen** (middel). R-D4 in drie stappen; past bij tablets, vouwtelefoons en de bureaubladmodus.
- **R-I4. Wachtrij voor apparaten die weg zijn** (middel). Nu "was offline" en zelf opnieuw sturen; met een wachtrij gaat het
  vanzelf zodra het apparaat terug is, zichtbaar bij Gedeeld.
- **R-I5. Hervatten na een herstart** (middel). Het aanbod bewaren, zodat een halve overdracht verder gaat (R-B27).
- **R-I6. Vertrouwen per apparaat** (middel). Bij het koppelen kiezen: eigen apparaat, of beperkt (geen wissen, geen ontgrendelen,
  geen bediening, geen wifi-wachtwoord zonder vraag). Antwoord op R-B22.
- **R-I7. Een plank aan de schermrand** (middel tot groot). Bestanden, tekst of plaatjes parkeren op een plank die op elk apparaat
  te zien is; erin slepen op het ene, eruit op het andere, ook zonder gedeelde muis en ook met de telefoon. **Hoeft voor nu
  niet** (Mark, 2026-11-14).
- **R-I8. Een ontvangen bestand zo uit de melding slepen** (middel). Het logische vervolg op een overdracht; Windows kan
  `W/dragsource.rs` hergebruiken.
- **R-I9. Tekst of een link op een apparaat laten vallen** (klein). Wordt klembord of "open daar"; `shareText` bestaat al.
- **R-I10. Gedeelde klembordgeschiedenis**: geschrapt (2026-11-14). De geschiedenis met vastzetten bestaat al op alle vier de
  apps (PARITY.md). Nieuw zou alleen zijn dat de lijst en wat is vastgezet op elk apparaat gelijk zijn; dat is de moeite niet.
- **R-I11. Een map volgen** (groot). Foto's van de telefoon gaan vanzelf naar een map op de computer of de Hub. Dit is in feite
  wat Syncthing doet (opmerking van Mark, 2026-11-14). Voorstel: niet bouwen; hooguit later alleen "nieuwe foto's van de telefoon
  naar een computer", in een richting.
- **R-I12. "Stuur als klembord" in het deelmenu van Android** (klein). Een plaatje komt op het klembord van de Mac in plaats van
  in Downloads; de herkomst `Clipboard` bestaat al.
- **R-I13. Gevoelig klembord herkennen** (klein tot middel). Wat uit een wachtwoordmanager komt krijgt een merk mee, en Android
  toont het dan niet in de voorvertoning. Vraagt een veld in het bericht.
- **R-I14. Opnieuw proberen en resterende tijd** (klein). Een knop bij een mislukte overdracht, snelheid en tijd bij een lopende.
- **R-I15. Tegel "Laatst ontvangen" op Android** (klein). Opent het nieuwste bestand of zet het op het klembord.

Uit O+ Connect van Oppo (2026-11-14, alleen gelezen in berichten en een paar ervaringen, niet zelf geprobeerd): het heeft geen
muis die over de schermrand naar de telefoon gaat. Slepen gaat daar via het venster met het scherm van de telefoon op de computer,
en via een bestandenlijst van de telefoon. Een tester noemt het slepen met de aanwijzer "soms lastig". Wat eruit te halen is:

- **R-I16. Laten vallen op het telefoonvenster** (middel). Een bestand dat je op het venster met het scherm van de telefoon laat
  vallen gaat naar de app die daar voor staat (bij Oppo: zo een chat van WhatsApp in). Tandem heeft dat venster al. De telefoon
  deelt het bestand dan met de app die voor staat (`ACTION_SEND` met dat pakket). Dit is de kortste weg naar "slepen naar de
  telefoon" en hoort bij P8.
- **R-I17. Bestanden van de telefoon op soort en recent, met zoeken** (middel). De pagina Bestanden toont nu alleen mappen. Oppo
  toont foto's, video's, documenten en "recent", en zoekt door de hele opslag. Kan met MediaStore op de telefoon.
- **R-I18. Uit de bestandenlijst van de telefoon slepen naar de computer** (middel). Is R-D13 voor bestanden op afstand.
- Niet over te nemen: tot vijf apps van de telefoon als losse vensters op de computer. Dat vraagt rechten die alleen de maker van
  de telefoon heeft.

Meer ideeen (2026-11-14):

- **R-I19. Sleepdoelen bij de aanwijzer** (middel). Zodra je een bestand begint te slepen verschijnen de apparaten als bolletjes
  vlak bij de aanwijzer; loslaten op een bolletje stuurt. Werkt zonder gedeelde muis en zonder het venster van Tandem te zoeken.
- **R-I20. Zwevend plaatje van een schermfoto van de telefoon** (klein tot middel). Een schermfoto komt als klein plaatje in de
  hoek van de computer, zoals macOS met zijn eigen schermfoto's doet; je sleept het zo een app in, of het verdwijnt vanzelf.
- **R-I21. Een paneel Onderweg** (klein). Een plek voor alles wat loopt, wacht op een ja, in de wachtrij staat of mislukt is, met
  Opnieuw en Stop. Hoort bij P2 en P3. **Gekozen door Mark (2026-11-14)**, zie P3b.
- **R-I22. Sturen tussen twee andere apparaten** (middel). Vanaf de Mac een bestand van de telefoon naar de Linux-pc laten gaan,
  zonder omweg over de Mac. Bestanden op afstand bestaan al; dit is een opdracht aan het ene apparaat om bij het andere op te halen.
  Voorbeeld: je zit achter de Mac en wilt een video van je telefoon op de Linux-pc hebben. Nu haal je hem eerst naar de Mac en stuur
  je hem door: twee keer overdragen en twee keer opslag. Hiermee zegt de Mac tegen de telefoon: stuur dit naar de Linux-pc.
  **Gekozen door Mark (2026-11-14)**, zie P9.

Horloge (2026-11-14): Mark vond deze ideeen niet goed genoeg, dus niet opnieuw voorstellen: vergrendelen bij weglopen en
ontgrendelen bij terugkomen, de kroon als draaiknop voor de computer, meldingen die je volgen, seintjes op de pols over je
apparaten, hartslag in beeld bij een opname, een zoeker op de pols, dicteren naar de computer.

Verder (2026-11-14, nog niet gekozen):

- **R-I30. Regels** (een pagina "als dit, dan dat"): komt er nooit (Mark, 2026-11-14). Niet opnieuw voorstellen.
- **R-I31. Open op het andere apparaat** (klein). In het deelmenu van Android en bij rechtsklik op de computers: het bestand gaat
  over en opent daar meteen. Voor links bestaat dit al (`open` in `ShareText`), voor bestanden niet.
- **R-I32. Sneller sturen zonder gedeeld netwerk** (groot). Zitten telefoon en computer niet op hetzelfde netwerk (onderweg, of
  een gastnetwerk dat apparaten van elkaar scheidt), dan gaat er nu alleen iets over als Tailscale aan staat; Bluetooth doet alleen
  kleine berichten, geen bestanden. Idee: voor een bestand zet de telefoon een tijdelijk eigen wifi-netwerk op (te onderzoeken:
  `LocalOnlyHotspot` van Android, zonder internet, zonder mobiele data en zonder dat een instelling verandert), geeft naam en
  wachtwoord over Bluetooth door, de computer stapt over, het bestand gaat op wifi-snelheid, en daarna gaat de computer terug naar
  zijn eigen netwerk. De prijs: de computer is zolang van het internet af. Tussen twee computers zonder telefoon kan het zo niet.
  Mark: klinkt goed (2026-11-14), nog niet gekozen.

### R-T. Testen op echte toestellen (voorstel)

Stand 2026-11-14: de Mac en de Oppo (Android 17, Tandem 0.3.15, aan adb) mogen gebruikt worden; de Xperia niet. Gedaan: R-B5.

Niets hiervan is op een toestel gezien. Mark kan de Xperia en de Oppo aan adb leggen en toegang geven tot de Linux-laptop en een
Windows-pc. Voorstel voor de volgorde, steeds met de dev-app en een eigen datamap:

1. Mac met Linux: R-D1 (naam), R-D5 en R-D6 (slepen uit Foto's, Mail en Safari), R-D7 (een bestand van 1 GB), R-D9.
2. Mac met Windows: R-B2 (Sturen naar met de naam van de Mac), R-D2 (vijf bestanden), R-D14 (tweede scherm).
3. Telefoons: R-B1 (met `adb shell am start` een deelverzoek met `EXTRA_SHORTCUT_ID`), R-B6 (een bestand naar Mac en Linux
   tegelijk), R-B7 (60 foto's), R-B15 (foto's uit de cloud), R-V1 (statusberichten tellen met het scherm uit), R-D4.
4. Mac alleen: R-B5 (Controlecentrum), R-B16 (een grote map), R-D17 (aanwijzer meten terwijl de app bezig is).

## Nieuwe functies (gekozen door Mark, 2026-10-29), nog te bouwen, op alle apparaten

- **De muis besturen met je ogen** (gevraagd 2026-11-10): de cursor volgt waar je kijkt, via de gewone camera; knipperen of even stil blijven kijken klikt. macOS heeft het zelf niet: ook in macOS 27 alleen Head Pointer (hoofd) en Dwell, eye tracking is er alleen op iPhone en iPad. Vergeleken (2026-11-10, niet zelf getest; geen enkel project noemt gemeten nauwkeurigheid):
  - **Basis: [EyeGestures](https://github.com/NativeSensors/EyeGestures)** (GPL-3.0, past bij onze AGPL-3.0): het verst ontwikkeld (658 sterren, 540 commits), Python en JavaScript met een Rust-engine (V4) die desktop en web delen. Die Rust-engine past in `tandem-core`, dus één keer voor Mac, Linux en Windows. Geeft een kijkpunt op het scherm; de muis zelf sturen doen wij al (de gedeelde muis).
  - **Voorbeeld voor de Mac: [shaal/eye-tracker](https://github.com/shaal/eye-tracker)** (MIT, klein en nieuw): MediaPipe-irispunten, kalibratie per persoon met ridge regression (9 punten, niet 5), knipperen of een knipoog klikt, de muis via CGEvent. Noemt eerlijk 1,5 graad (ongeveer 45 px) als plafond voor een webcam zonder infrarood.
  - **Ideeën: [EyeTrax](https://github.com/ck-zhang/EyeTrax)** (MIT): kalibratie in 9 of 5 punten, lissajous of dicht raster, en afvlakking met Kalman. Niet gekozen: [GazeTracking](https://github.com/antoinelame/GazeTracking) geeft alleen een kijkrichting, eye-mouse-tracking heeft geen licentie.
  - Op de Mac kan ook Vision van Apple de pupillen vinden (`VNFaceLandmarks2D`), zonder MediaPipe. Eerlijk naar Mark: een webcam raakt grote knoppen, geen kleine menu's of tekst. Op elk apparaat met een camera: de computers en de telefoon (die als camera voor een computer zonder).
- **Speelser Material You, zoals PixelPlay** (gevraagd 2026-11-08, voor heel Tandem): Mark noemt de muziekspeler PixelPlay (com.theveloper.pixelplay) als voorbeeld. Tegels met eigen tonale kleuren (primary-, secondary- en tertiaryContainer), iconen in een eigen rondje, grote getallen met korte tekst, speelse vormen in plaats van strak. Aanleiding: de rusthartslag was slecht leesbaar. De horlogepagina van de telefoon is al zo (branch claude/horloge-link); de rest van de app en de computers volgen.
- **Back-up, wat nog kan** (2026-11-08): de andere apparaten bewaren iconen, vastgezet en geluid per apparaat onder de oude id van een gereset apparaat. Na opnieuw koppelen kan "dit was vroeger X" die overzetten. Ook de instellingen die alleen op het horloge staan (muissnelheid, knijpen, slaap vanzelf herkennen) via de telefoon in de back-up.

- **Status-chips op de apparaatpagina** (gemeld 2026-11-06): de rij chips bovenaan loopt soms door naar een tweede regel. De knoppen zelf zijn gedaan: computers hebben op de Mac drie kolommen van vier, wat niet kan is grijs en zegt waarom. Op Linux en Windows staan ze nog als losse knoppen achter elkaar, niet in kolommen; op Android is het een lijst.
- **Uit de securitycheck van 2026-11-05, nog te doen**: Dependabot staat uit op de repo en `cargo audit` is hier niet geinstalleerd, dus afhankelijkheden zijn niet nagelopen (keuze van Mark). Op de Mac telt een gekoppeld apparaat zonder "nooit" als mag bedienen, op Linux en Windows moet `remote_input` per apparaat aan: gelijktrekken? `Dial` belt zonder vraag voor elk gekoppeld apparaat. `networksetup` krijgt het wifi-wachtwoord als argument als `CoreWLAN` faalt.
- **`tandemd` met een lang pad**: is de map van `--data-dir` te lang voor een Unix-socket, dan start elk commando stil een eigen engine in plaats van de draaiende te vragen. Moet een duidelijke fout worden.
- **Garmin Forerunner 255 in Tandem** (gevraagd 2026-11-05): het horloge blijft zoals het is, zonder eigen app erop. Tandem op de telefoon
  spreekt Garmins eigen Bluetooth-protocol (GFDI, nagebouwd, niet officieel): meldingen, bellen, muziek, en slaap, stappen en trainingen
  als FIT-bestanden. Gadgetbridge laat zien dat het kan: het noemt de 255 en 255 Music "mostly supported", koppelen zonder Garmin-app, en
  slaap via de widgets. Eerst met Gadgetbridge op een echt horloge kijken hoe goed het is. Gadgetbridge is voor zover bekend AGPL, dus de
  licentie bekijken voordat er code wordt overgenomen. Muis, pinch en eigen analyse van ruwe slaapdata kunnen hiermee niet; de muis kan
  misschien met een Connect IQ-app (Bluetooth als centrale, de 255 staat op Garmins lijst, gyrosnelheid nog niet nagekeken).
- **Sonos op het horloge** (gevraagd 2026-11-06): kijken of de Sonos-bediening uit de PixelPlayer-mod ook op de Pixel Watch kan, via de telefoon.
- **De hele dag hartslag bijhouden en inslapen daaraan herkennen** (gevraagd 2026-11-06): continu meten en conclusies trekken, zoals de daling als iemand gaat slapen, in plaats van steeds bedtime mode aanzetten. Bedtime mode wordt dan een aanwijzing dat iemand bijna gaat slapen. Eerst meten wat het de batterij kost.
- **Rustig wakker worden met het horloge** (gevraagd 2026-11-06): instelbaar dat het horloge vlak voor de wekker op de telefoon zacht begint te trillen en langzaam sterker wordt, zodat je al een beetje wakker bent als de wekker afgaat. De telefoon kent de volgende wekker en geeft die door aan het horloge.
- **Middagdutjes herkennen** (gevraagd 2026-11-06): de automatische slaapherkenning luistert nu alleen van 21:00 tot 11:00. Eventueel ook overdag, als dat geen batterij of foute meldingen kost.
- **Slaapscore en readiness-score** (gevraagd 2026-11-06): één cijfer per nacht uit duur, onderbrekingen, fasen en regelmaat, en elke ochtend een readiness-score uit die nacht, je hartslag in rust tegenover je eigen gemiddelde en de dagen ervoor. Eerst genoeg eigen nachten verzamelen en eerlijk zijn over wat ontbreekt: HRV hebben we nu niet, omdat de ruwe PPG is afgeschermd.
- **Een eigen plek voor gezondheid** (gekozen 2026-11-06): slaap, stappen en trainingen staan nu door de rest heen. Op de telefoon en computers een eigen pagina, op het horloge een tweede icoon uit dezelfde app. Alleen te zien met een gekoppeld horloge, of als het handmatig is aangezet in de instellingen.
  - Moet erin: dutjes, slaapscore (staat hieronder ook), tijdlijn van de nacht, regelmaat van bedtijden, rusthartslag, een seintje als rusthartslag en huidtemperatuur afwijken, hartslag de hele dag, stappendoel en actieve minuten, weekoverzicht, slim wekken, exporteren, en importeren uit Health Connect.
  - Hoeft niet: slaapschuld en bedtijdadvies, "wat helpt jouw slaap", ademoefeningen.
- **Gezondheid, volgende ronde** (gevraagd 2026-11-09, na de nieuwe indeling met overzicht en een pagina per deel; op alle apparaten):
  - Een maand- en jaaroverzicht naast de week, voor slaap, stappen, actieve minuten en rusthartslag. **Maand gedaan** (2026-11-10): een week terugbladeren en een maandkalender per onderdeel. Een jaaroverzicht nog niet.
  - Het verloop van de huidtemperatuur per nacht: het horloge meet die al. Tegenover je eigen gewone waarde, niet als absoluut getal.
  - HRV. Eerst uitzoeken of het kan: de ruwe PPG is afgeschermd (zie de slaapscore hierboven), dus misschien alleen via Health Services of helemaal niet. Eerlijk zeggen als het niet kan.
  - Een slaapdoel en een herinnering om naar bed te gaan. **Standaard uit.** Een herinnering die je zelf aanzet, geen advies, dus geen botsing met "bedtijdadvies hoeft niet" hierboven.
  - Reeksen, zoals het aantal dagen op rij dat je stappendoel is gehaald.
  - Een pagina per training met hartslagzones (tijd per zone, uit je maximale hartslag).
  - Een weekoverzicht als melding, bijvoorbeeld op zondagavond, met slaap, stappen en rusthartslag tegenover de week ervoor.
- **Sport op het horloge** (gekozen 2026-11-05): trainingen met GPS op de Pixel Watch 2, met afstand, tempo en route, en die gegevens
  naar telefoon en computers, net als de slaapgegevens.
- **Testen zodra de Linux-laptop aan staat: een gesprek op de laptop** (gevraagd 2026-11-04): de laptop als Bluetooth-headset van de Xperia,
  met de rol `hfp_hf` van PipeWire. Eerst kijken of dat los van Tandem echt werkt: verbinden, spraak beide kanten op tijdens een echt
  gesprek, en wat er gebeurt met een Bluetooth-headset aan de laptop. Werkt het, dan pas de knop in het belkaartje op Linux bouwen.
- **Inloggen op afstand op een vergrendelde Mac** (gevraagd 2026-11-01): gebouwd in 0.2.61. De Mac logt zelf in op zijn eigen Scherm delen
  (127.0.0.1, Apple-login met het wachtwoord uit de sleutelhanger) en stuurt dat beeld als gewone H.264-sessie, dus elke kijker werkt.
  Na ontgrendelen gaat de sessie verder met de gewone opname. Werkt niet na een herstart, uitloggen of slaap.
- **Foto bij een gesprek** (gevraagd 2026-11-02): de foto van het contact, of de bitmoji/profielfoto uit de melding van een app als
  Snapchat (`EXTRA_CALL_PERSON` met zijn icoon, of het grote icoon), klein meesturen met het gesprek en tonen op het belkaartje van
  Mac, Windows en Linux. Vraagt een nieuw veld in `CallMsg`.
- **Slapen melden op Windows en Linux** (2026-11-02): de Mac zegt nu "slaapt" voor hij slaapt; Windows en Linux zeggen niets, en
  heten dan offline. Linux met een delay-inhibitor van logind, Windows met `PBT_APMSUSPEND`.
- **Mappen versturen, vanaf elk apparaat** (gevraagd 2026-11-01): een hele map sturen, met de mappen erin, van en naar Mac, Android,
  Windows en Linux.
- **Linux vraagt steeds toestemming** (gemeld 2026-11-01, opnieuw 2026-11-06): de InputCapture-portal van Fedora 44 is versie 1 en kan niets onthouden. Tandem opent die sessie daar nu pas als iemand op Start drukt onder de schermen of de gedeelde muis aanzet. Nog na te gaan: wanneer GNOME toch weer vraagt om scherm en invoer, want daarvoor bewaart Tandem al tokens. Eventueel een snelle Start in het traymenu.
- **Ontgrendelen met een vingerafdruk** (gevraagd 2026-11-01): gebouwd in 0.2.65 voor de Mac als bron, Android en de Mac als kijker,
  in 0.2.66 ook een vergrendelde Linux-pc (logind) met een knop Ontgrendel op zijn pagina. De
  telefoon controleert de vingerafdruk en de Mac gaat daarop af. Open: Windows Hello op een Windows-kijker (vraagt de `windows`-crate,
  eerst aan Mark vragen), en het echte inlogscherm van macOS is nog niet met de hand geprobeerd.
- **Vergrendelde Windows- en Linux-pc bereiken** (gevraagd 2026-11-01): Windows kan het met een eigen dienst die als SYSTEM draait,
  het inlogscherm (Winlogon) vastlegt en de toetsen doorgeeft, zoals Chrome Remote Desktop en RustDesk; eenmalig installeren met
  beheerdersrechten. Linux: op GNOME (Wayland) kan het niet, getest 2026-11-01: Mutter laat geen nieuwe sessie van GNOME Remote Desktop
  toe zolang het scherm vergrendeld is. Alleen een sessie die al openstond voor het vergrendelen blijft werken, met het deel-icoon
  van GNOME steeds in beeld. X11 (met een eigen dienst) en KDE nog niet geprobeerd. Alleen te testen op een echte pc.
- **Mac dichtgeklapt laten werken** (gevraagd 2026-11-01): wakker houden zoals Amphetamine. Inactief slapen tegenhouden kan zonder
  beheerder; dichtgeklapt wakker blijven vraagt `pmset disablesleep` met het beheerderswachtwoord, en zonder scherm is er niets om
  op te nemen (een scherm van software zou dat oplossen).
- **Met de Developer ID (gekozen 2026-10-29, eerst releases ondertekenen en notariseren met team X8Y2M32MNQ; 0.2.43 neemt die al aan):**
  1. Telefoon als echte webcam op de Mac (Camera Extension) en op Windows (zie verderop). Mac gebouwd (2026-10-29, `macos/CameraExtension`,
     `VirtualCamera.swift`): komt alleen in de app met het provisioning profile van de Developer ID (System Extension), secret
     `DEVELOPER_ID_PROFILE_B64`. Een app die Tandem Camera kiest zet de telefooncamera aan zonder venster.
  2. Telefoon als microfoon van de computer: een invoerapparaat in macOS (Audio Server-plugin), op Windows en Linux een virtueel
     invoerapparaat. Mac en Linux gebouwd in 0.2.53 (`macos/AudioDriver`, `PhoneMicrophone.swift`, `virtual_audio.rs`), nog niet
     gehoord. Windows staat in PARITY.md.
  3. Telefoon als echte geluidsuitvoer: in het geluidsmenu van macOS zoals AirPods, in plaats van het afgetapte systeemgeluid; Windows
     en Linux hetzelfde als uitvoerapparaat. Mac en Linux gebouwd in 0.2.53 (Tandem Speaker, `SpeakerCapture.swift`), nog niet gehoord.
  4. De bestanden van de telefoon als vaste plek in de Finder-zijbalk (File Provider), in plaats van een gekoppelde schijf; Windows
     en Linux zo dicht mogelijk daarbij (Cloud Files API, een GVfs- of FUSE-mount). Mac gebouwd (2026-10-29,
     `macos/FileProviderExtension`, `FilesInFinder.swift`), nergens geïnstalleerd gezien: komt alleen in de app met de Developer ID en
     het profile (zelfde voorwaarde als de camera, want de app krijgt de app group daar), anders blijft de gekoppelde schijf. Linux
     gebouwd (`sidebar.rs`: GVfs-mount en een GTK-bladwijzer), nooit gezien. Nog te doen: **Windows met de Cloud Files API**
     (`CfRegisterSyncRoot`, een sync root in de navigatie van de Verkenner met plaatshouders die bij openen worden opgehaald), een
     grote klus; tot dan is de knop daar grijs en zegt waarom.
  5. "Stuur met Tandem" bij rechtsklik in Finder (Finder Sync of een Services-menu), in de Verkenner van Windows en in Nautilus.
     Bestond al: het Services-menu van de Mac (`NSServices` in Info.plist), `sendwith.rs` voor Nautilus, Dolphin, Nemo en "Sturen naar".

- **Presentatie-afstandsbediening** (gebouwd in 0.2.46, op de emulator getest): volumeknoppen (en knoppen op het scherm) zijn volgende en vorige dia op de computer. Een pointer
  die met de gyroscoop van de telefoon beweegt zolang je de pointerknop ingedrukt houdt: wijzen met de telefoon, een stip op het scherm
  van de computer. Loslaten en de stip is weg.
- **Batterijmeldingen** (gebouwd in 0.2.44, de keten getest met de emulator): een melding op de andere apparaten als een apparaat vol is of bijna leeg (rond 15%). Aan en uit in Instellingen.
- **Media uit tijdens een gesprek** (gebouwd in 0.2.45; computer-in-gesprek alleen op de Mac): zit je in een gesprek (telefoon of call op de computer), dan pauzeert de muziek en video op de
  andere apparaten vanzelf, en na het gesprek gaat hij weer verder als hij liep. Aan en uit in Instellingen.
- **Wifi doorgeven met één tik:** een apparaat in je circle krijgt het wifi-netwerk van een ander apparaat zonder wachtwoord te typen
  (op Android leest Shizuku de opgeslagen netwerken).
- **Telefoon als webcam op de Mac** (Mark heeft nu een Apple Developer-account, 2026-10-29): een camera-extensie (CMIOExtension) in de
  app. Vraagt een Developer ID-certificaat, notarisatie en een overgangsrelease, want de updater vergelijkt de handtekening van de nieuwe
  app met die van de draaiende (`Tandem Signing` wordt Developer ID).
- **Telefoon als webcam op Windows:** een virtuele camera met `MFCreateVirtualCamera` (Windows 11), zodat Zoom, Teams en OBS hem zien.
- **De telefooncamera als bron in OBS** (gebouwd in 0.2.47 als MPEG-TS over UDP in plaats van SRT, nog niet in OBS gezien): Tandem zet het beeld als stream op de eigen computer (`srt://127.0.0.1:...`), in OBS een
  Mediabron met dat adres; een knop "Kopieer OBS-adres". Op Mac, Windows en Linux.

## Horloge: tweede ronde (gekozen door Mark, 2026-11-07), nog te bouwen

Uit een lijst van ideeen koos Mark deze. Zelfde regels als de ronde hieronder: nieuwe berichten achteraan in `Messages.kt`, wat een
computer nodig heeft op Mac, Linux en Windows tegelijk, wat alleen op telefoon en horloge kan in `docs/PARITY.md`.

- **Eigen klok op het horloge**: timer, stopwatch en een stille wekker op de pols, zodat de klok van Google uit kan.
- **Telefoon vergeten**: punt 1 hieronder, met de meting die daar staat.
- **Wekker uitzetten of snoozen vanaf de pols**: als de wekker van de telefoon afgaat. Eerst nakijken of de klok-app van LineageOS
  zijn melding knoppen geeft die een andere app mag indrukken.
- **Inlogcode groot op de pols**: een code uit een sms of melding, groot en met een knop om hem op de computer te plakken.
- **Kaarten en QR-codes**: tickets en klantenkaarten op de telefoon gekozen, op het horloge te tonen zonder verbinding.
- **Cameraknop**: de telefoon opent de camera als die dicht is en maakt meteen een foto; staat de camera al open, dan alleen de foto.
  Met een timer die je op het horloge kiest. Eerst nakijken hoe de camera-app van de telefoon zich laat aansturen.
- **Huidtemperatuur**: per nacht als trend op de pagina Gezondheid, uit de metingen die het horloge al opneemt.
- **Beweegseintje**: een trilling na lang zitten, met uurblokjes op de pagina Gezondheid.
- **Muziek stopt als je slaapt**: punt 5 hieronder.

### Gevraagd door Mark, 2026-11-07 (avond)
- **De horlogemuis als muis van de computer zelf (Bluetooth), af te maken.** Werkt als proef (`HidMouse`): koppelen, verbinden, bewegen
  met de onderarm. Nog: elke computer geeft zijn Bluetooth-adres via de telefoon door, zodat Muis meteen met die computer verbindt; een
  knop "Horloge als muis" op elke computer die het horloge wekt en het muisscherm opent.
- **Geluid mag niet vanzelf naar het horloge.** Na het koppelen als muis zag macOS het horloge als headset (HFP) en stuurde het geluid
  erheen. Het moet een keuze zijn.
- **Het horloge als luidspreker kiezen, vanaf elk apparaat.** Op de Mac via de Bluetooth-koppeling (handsfree-kwaliteit); uitzoeken wat
  er op Windows, Linux en de telefoon kan.
- **Tafelmodus.** De hand op tafel alsof hij een muis vasthoudt, tikken op tafel om te klikken. Links en rechts volgt de onderarm; voor en
  achter is moeilijk, omdat een horloge schuiven slecht meet. Eerst een opname.
- **Knijpen en vasthouden kan het horloge niet voelen**; slepen gaat met de vinger op het scherm.

## Horloge: gekozen functies, uitgewerkt (gekozen door Mark, 2026-11-06), nog te bouwen

Bouwt voort op `claude/horloge-link` (zie `docs/WATCH.md` daar). Het horloge blijft geen lid van de circle: alles naar een computer loopt
via de telefoon, met een eigen schakelaar in Tandem op de telefoon. Nieuwe berichten komen achteraan in `Messages.kt`, oude versies slaan
ze over. Wat een computer nodig heeft, komt op Mac, Linux en Windows tegelijk. Wat alleen op telefoon en horloge kan, krijgt een regel in
`docs/PARITY.md`. Volgorde van bouwen: de stille meting bij punt 1 loopt mee, dan 2, 5, 8 en 3 (na de proef daar), en 1 alleen als de meting goed is. Niet gekozen: goedkeuren
op je pols, presenteren met de kroon, spraaknotitie.

- **1. Telefoon vergeten**
  - Wat: loop je weg zonder telefoon, dan trilt je pols één keer en staat er een kaart met "Laat rinkelen". Die knop laat de telefoon
    rinkelen zodra hij weer in bereik is (`FindDevice`).
  - Het probleem (opmerking van Mark): thuis kan de link ook wegvallen, bijvoorbeeld als de telefoon op een andere verdieping ligt. Met maar een paar
    stappen zou het dan vaak onterecht trillen. Of dit handig genoeg is, weten we pas na een meting.
  - Eerst meten: een week lang alleen op het echte horloge in een logbestand zetten wanneer het zou trillen, zonder te trillen. Mark bekijkt dan welke momenten
    terecht waren. Veel onterecht thuis? Dan niet bouwen.
  - Wanneer (voorstel voor de meting): de link is weg (`onLinkDown` in `WatchLink`) en je zet daarna binnen 5 minuten minstens 200 stappen (`StepTracker`),
    ongeveer 150 m. Rondlopen in huis haalt dat meestal niet. Op de fiets of in de auto zet je geen stappen en merkt het dit dus niet.
  - Niet: tijdens een slaapopname, aan de lader, of als het horloge af is (die toestanden kent het horloge al). Eén seintje per keer weg.
  - Nieuw: alleen op het horloge, geen nieuw bericht. Een schakelaar op het horloge, **standaard uit**.
  - Nog bekijken: of Bluetooth uitzetten op de telefoon vals alarm geeft als je dan gaat lopen. Zo ja, een kort afscheidsbericht van de telefoon.
- **2. Seintje van je computer**
  - Wat: `tandem notify "Render klaar"` op de computer laat je pols trillen, met de naam van de computer en die tekst. Zonder verbonden
    horloge wordt het een gewone melding op de telefoon. Is er geen telefoon bereikbaar, dan is het seintje weg en geeft de opdracht een
    foutcode, zodat een script dat merkt.
  - Waarvoor: DaVinci kan na een render een script starten (op versie 21.1 nakijken), Claude Code met een hook als een sessie op je wacht,
    en elk eigen script.
  - Ingang: Linux en Windows via `tauri_plugin_single_instance`, dat de argumenten al aan de draaiende app geeft. `tandemd` als extra
    commando. Op de Mac een Unix-socket met alleen rechten voor de gebruiker, zoals bij `tandemd`. Bewust geen `tandem://`-adres: dan kan elke website je pols laten trillen.
  - Computerkant gebouwd op `claude/horloge-computer`. Telefoonkant nog te doen.
  - Kern: de bestaande `send_notification` met een eigen app-id. `TandemService.kt` doet nu niets met `TandemEvent.Notification`; daar
    komt de doorgifte naar `WatchNotifications`. Eerst nakijken of de telefoon die meldingen elders verwerkt.
  - Grenzen: tekst hooguit 200 tekens, hooguit één seintje per 10 s per computer, per computer uit te zetten op de telefoon.
  - Pariteit: Android als bron heeft geen opdrachtregel, dat komt als uitzondering in `docs/PARITY.md`.
- **3. Snelknoppen op het horloge**
  - Wat: een tegel met hooguit vier knoppen die je zelf kiest, in Tandem op de telefoon, bij de instellingen van het horloge. De indeling volgt het aantal.
    Keuzes: computer vergrendelen, computer wekken, computer wakker houden, hotspot aan of uit, geluidsstand van de telefoon, telefoon laten rinkelen, stille slaap.
    Standaard de eerste twee, plus hotspot en geluidsstand. Met meer computers kies je er een op het horloge; de laatste keuze blijft staan.
  - Bericht: nieuw `QuickAction` (soort, doel-id) van horloge naar telefoon, die de bestaande telefooncode aanroept (`lock`,
    `setSoundMode`, Wake on LAN, hotspot). Nieuw `QuickState` van telefoon naar horloge: de gekozen knoppen, geluidsstand, hotspot, en per computer of hij kan
    vergrendelen, slaapt of verbonden is.
  - Wat niet kan: de knop is grijs en zegt bij het tikken waarom, zoals overal in Tandem. Elke knop trilt kort als het gelukt is.
  - Schakelaar per computer op de telefoon. Vergrendelen mag nu al van elk gekoppeld apparaat, wekken ook; deze knoppen voegen geen nieuwe macht toe.
  - Vraagt: `androidx.wear.tiles` en `protolayout`, dus akkoord van Mark. Dezelfde knoppen komen ook als scherm in de app, zodat ze ook werken als tegels niet kunnen.
  - **Eerst een proef van een uur op het echte horloge**: een lege tegel. Pas als die op de Pixel Watch 2 verschijnt, wordt de tegel gebouwd.
- **4. Batterijen op de wijzerplaat**: nog niet (besluit van Mark, 2026-11-06). Kan later op de `DeviceBatteries` die al binnenkomen.
- **5. Muziek stopt als je slaapt**
  - Wat: slaap je in, dan pauzeert alles wat speelt: op de telefoon, op verbonden computers en via de telefoon ook Sonos, als dat daar als mediasessie loopt.
  - Wanneer: tijdens een slaapopname, na 25 minuten stil (de drempel van `SleepAutoDetector`), dus niet meteen als je bedtime mode met de hand aanzet.
  - Bericht: nieuw `Asleep` (id van de nacht) van horloge naar telefoon. `SleepMode` kan dit niet dragen: die wordt na elke verbinding herhaald. Het id zorgt dat het één keer per nacht gebeurt. Was de link weg, dan telt het bericht alleen als het jonger is dan 10 minuten.
  - Telefoon: `WatchMedia` pauzeert de eigen spelers, `sendMediaCommand` die op computers, alleen wat echt speelt.
  - Schakelaars op de telefoon, naast "Stille slaap": "Muziek pauzeren" (standaard uit) en apart "Computers vergrendelen" (standaard uit), want iemand anders kan dan achter de computer zitten. **Gebouwd (0.3.2):** op de pagina van het horloge op de telefoon; de telefoon pauzeert zijn eigen spelers en die van de computers, en vergrendelt de computers die online zijn.
  - **Gebouwd (0.3.2).** De vraag na een nacht (wens van Mark): staat "Muziek pauzeren" uit en speelde er nog minstens 30 minuten muziek nadat je in slaap viel, dan krijg je na het opstaan een melding: "Er speelde muziek door terwijl je sliep. Voortaan vanzelf uitzetten?" Knoppen: "Ja, voortaan", "Nu niet", "Niet meer vragen". "Ja" zet de schakelaar aan. "Nu niet" vraagt pas na een volgende keer opnieuw, hooguit één keer per week. `Asleep` wordt daarvoor altijd gestuurd, ook als de schakelaar uit staat; de telefoon onthoudt alleen of er toen iets speelde.
  - Later: de betere herkenning uit "De hele dag hartslag bijhouden" neemt de drempel van 25 minuten over.
- **8. Muziek op je computer bedienen**
  - Wat: de horloge-player toont ook wat op een computer speelt, met de computernaam erbij. Afspelen, volgende, vorige en de kroon voor het volume werken daar ook.
  - Telefoon: `MediaMirror` krijgt de spelers van computers al binnen (`TandemEvent.MediaPlayers`, `_remote`). `WatchMedia` voegt die samen met de eigen spelers via `tandem_media_visible`, met het apparaat-id vooraan in het speler-id. `perform` stuurt zo'n speler door met `sendMediaCommand`.
  - Volgorde: `PlayerRules` blijft gelden: wat speelt vooraan, hooguit drie spelers. Hoes: `MediaArt` van de computer door `smallCover` (WebP van 152 bij 152, hooguit 6 KB).
  - Kern: `MediaAction` heeft geen volume. Nieuw `VolumeUp` en `VolumeDown`; oudere apparaten lezen dat als `Other`. Mac, Linux en Windows zetten het volume van het systeem.
  - Computerkant gebouwd op `claude/horloge-computer`: Linux en Windows sturen nu hun spelers, alle computers hebben het volume. Nog niet op een echte Linux- of Windows-computer gezien. De hoes ontbreekt daar nog (art 0): MPRIS geeft een adres, Windows een stream. Telefoonkant nog te doen; bekijk wat er in `MediaMirror` achter `share` zit, die instelling blijft gelden.

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
- Menubalkpaneel en sneltoetsen (gevraagd 2026-10-29): op de Mac in 0.2.33, een kaart met de apparaten, vier vakjes naar keuze en een lijst
  sneltoetsen voor elke functie (Instellingen, Sneltoetsen). Linux en Windows: het trayvenster op dezelfde manier indelen; sneltoetsen in elke app
  vragen daar de plugin global-shortcut van Tauri, die nog niet in de afhankelijkheden zit.
- Bellen als eigen plek in de zijbalk (gevraagd 2026-10-27): Berichten staat sinds 0.2.30 bij Bestanden en Meldingen, bellen zit nog op de
  pagina Berichten (contacten met een belknop). Een rij Bellen met de recente gesprekken van de telefoon vraagt READ_CALL_LOG op Android en
  een nieuw bericht in de core; op Mac, Windows en Linux tegelijk.
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
  Vanzelf controleren (gevraagd 2026-10-24, gebouwd, nergens op echte toestellen gehoord): met sweeps tussen 18,5 en 20 kHz die je niet hoort, bij een
  tweede scherm dat start, een apparaat dat terugkomt of bewogen is, de Mac die wakker wordt, de muis die er na 5 minuten weer heen gaat en elke 5
  minuten tijdens een tweede scherm (`side_check.rs`, metingen in docs/SIDE.md). Alleen via de eigen speakers, verschuift pas als twee controles na
  elkaar dezelfde andere kant horen, met een toast. Instelling "De kant vanzelf controleren" op de Mac (Muis) en op Linux en Windows. Nog niet: op Windows zien of het geluid gedempt is.
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
- Elk apparaat moet de geluidsuitvoer van elk ander kunnen zijn (gevraagd 2026-10-24, gebouwd, alleen gecompileerd en nergens gehoord): op de pagina
  van elk apparaat dat geluid afspeelt (`audio.play`, nu ook Android, Linux en Windows; `audio.send` zegt dat een apparaat zijn eigen geluid kan sturen)
  staat een knop: Mac "Gebruik als speaker" bij een computer (en Telefoon als uitvoer zoals al), Linux en Windows "Gebruik als speaker", Android "Stuur
  het geluid van deze telefoon naar". Een druk stopt het weer. Een zender heeft een uitvoer tegelijk (een nieuwe neemt de plaats in), een speler speelt
  er meer tegelijk (Mac een audio-engine per zender, Android een `AudioTrack` per zender onder een audiofocus en een melding, Linux en Windows een
  cpal-stroom per zender; het systeem mengt). Opnemen: Mac de tap, nu zonder Tandem zelf (geen echo, en het geluid van anderen blijft hoorbaar als de Mac
  stil staat); Linux `parec` of GStreamer van de monitor; Windows WASAPI-loopback; Android de playback capture met `excludeUid` van Tandem. Stil bij de
  zender: alleen de Mac (de tap dempt). Linux, Windows en Android spelen door, met die mededeling bij het starten. Een apparaat speelt en stuurt nooit
  tegelijk met hetzelfde andere apparaat, dus een stop zegt altijd wat hij beëindigt; een nee van de speler komt bij de zender als melding met de reden.
  Geen nieuw bericht in het protocol. Nog niet: stil maken op Linux (een null-sink), Windows en Android; een kring van drie apparaten via Linux of Windows
  kan rondzingen.
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
- Scherm uitbreiden naar Android ook staand (gevraagd 2026-10-24): gebouwd. Een Mac of Linux die `screen.extend.turn` meldt draait het extra scherm
  mee met de telefoon, binnen dezelfde sessie (`MediaResize`, zonder nieuwe vraag); de Mac draait het scherm van software op zijn plek met zijn
  vensters (`applySettings:`, gezien op de echte Mac met `TANDEM_DEBUG_VDISPLAY=2340x1080:turn`), Linux maakt de monitor opnieuw in de nieuwe vorm.
  Oudere computers houden het liggend. Nog te zien op echte toestellen: draaien met een Mac en met de Linux, de vensters en de kant (zie SCREEN.md).
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
  4. Wifi delen: gebouwd (2026-10-24), omgebouwd (2026-10-27). Elk apparaat meldt zijn netwerk. Een apparaat zonder enig netwerk vraagt
     "De wifi van X gebruiken?" voor een netwerk dat het nog niet kent; dat ja is de enige vraag, de ander geeft het wachtwoord, ook over
     Bluetooth. Wifi delen op de pagina van een apparaat, ook tussen computers. Van telefoon naar computer met Shizuku (de shell leest
     opgeslagen netwerken); zonder Shizuku meldt de telefoon zijn netwerk als `locked` en biedt niemand het aan.
  5. Foto's van de telefoon vanzelf op de computer, zoals iCloud-foto's maar lokaal (schermafbeeldingen gaan al). Haalbaar.
  6. Goedkeuren met de telefoon: geschrapt door Mark (2026-10-24), ziet het nut niet.
  7. De computer laten piepen en zien waar hij is vanaf de telefoon (Find My voor laptops), met het netwerk waar hij laatst was. Het geluid komt uit
     de eigen systeemgeluiden van elk apparaat en is in de instellingen te kiezen (Mac `/System/Library/Sounds`, Linux het geluidsthema, Windows
     `C:\Windows\Media`, Android de kiezer van het systeem). Gekozen door Mark. Gebouwd (2026-10-24): Speel geluid af op de pagina van elke
     computer met laatst gezien en wifinaam (`Ring`, `Ringing`, cap `ring`, `seen.cbor`), nog niet op echte toestellen geprobeerd.
  8. Focus en Niet storen gelijk op alle apparaten. Gebouwd (2026-10-24, op de Android-emulator gezien, niet op echte toestellen): Android een eigen
     modus "Focus van <apparaat>", Linux GNOME en KDE, Windows alleen lezen, Mac lezen en zetten met de eigen opdracht Tandem Focus (één klik, vraagt iCloud).
  9. Een tablet met pen als tekentablet voor de Mac (druk en kanteling, zoals de Apple Pencil bij Sidecar). Haalbaar met de invoer van een tweede scherm.
  10. Bellen met het geluid op de computer (microfoon en luidspreker van de Mac). Via Bluetooth kan het op de Mac niet (getest op 2026-11-04: de handsfree-verbinding komt
      op, macOS weigert het spraakkanaal, zie `docs/PARITY.md`). De weg via de telefoon zelf (de systeem-API's voor het afvangen van gespreksgeluid, met de rechten van de
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
- Een kaartje onder de aanwijzer op Linux na slepen van de Mac (gevraagd 2026-10-24). Gebouwd (`dragcard.rs` in de app en in winsys): de
  bestanden landen in Downloads zoals voorheen, met de melding, en onder de aanwijzer staat 6 seconden een kaartje met een plaatje van het
  bestand (bij meer een stapel met het aantal). Wie het kaartje pakt sleept de bestanden verder, naar een map, een mail of een websiteveld;
  losgelaten waar niets ze neemt glijdt het terug. Het is een X-venster (XWayland) en de sleep is XDND, met een druk van de persoon zelf, zodat
  GNOME hem mag doorgeven aan Wayland-vensters. Op een X-server in een container getest met een GTK-venster als doel; op GNOME nog nooit gezien.
  Het logboek schrijft elke stap met `dragcard:` ervoor.
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
  - Scherm uitbreiden naar Android ook staand, en het volgt de draaiing van de telefoon (gebouwd, zie hierboven).
  - Bij elk uitbreiden en elke keer dat de muis overgaat even controleren aan welke kant een apparaat staat (zonder hoorbare piep, als dat
    kan).
  - De zoekbalk van Bestanden gelijk aan die van Klembord.
  - Elk apparaat moet de geluidsuitvoer van elk ander kunnen zijn, niet alleen een telefoon voor de Mac (gebouwd, zie hierboven).
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
- Het geluid van telefoongesprekken (praten via de microfoon en speakers van een computer) valt buiten Tandem (besloten 2026-10-25). Tandem
  doet alles rond een gesprek: het belscherm, opnemen, weigeren, ophangen, bellen en contacten zoeken. Op Windows doet Phone Link het
  geluid, op Linux kan de laptop via de Bluetooth-instellingen van GNOME zelf de headset van de telefoon zijn, los van Tandem.
- Later, als Mark een betaalde Apple Developer-account neemt (gezegd 2026-10-25): de telefoon als webcam op de Mac (een camera-extensie
  vraagt een Developer ID), notarisatie zodat "Toch openen" bij de eerste installatie weg is, de Focus-status van macOS lezen zonder de
  snelkoppeling in Opdrachten (en dus zonder iCloud), en meldingen als echte communicatiemeldingen. Webcam op Linux en Windows nu al bouwen.
- Webcam op Linux gebouwd (2026-10-25, `webcam_start` in `live.rs`): de knop "Gebruik als webcam" op de pagina van de telefoon vraagt de
  voorcamera, GStreamer draait het beeld rechtop en geeft het aan PipeWire als bron met de rol camera ("Pixel 9 (Tandem)"). Een PipeWire-bron
  van GStreamer stopt als de laatste app de camera loslaat; Tandem start hem dan opnieuw. In een container met PipeWire 1.4 en WirePlumber 0.5
  gelezen zoals een browser dat doet (staand beeld, juiste maat). PipeWire 0.3.65 (Debian 12) crasht erop. Windows nog niet (`MFCreateVirtualCamera`).
- Nog te controleren op echte toestellen: sectie 0.

## 0. Eerst op echte toestellen bekijken (kost weinig, voorkomt bouwen op zand)

- **Android: knoppen onderin doen niets na koppelen via een link** (gevonden 2026-11-05): een `tandem://`-link opende een tweede
  `MainActivity` bovenop de eerste. Daarna deden de tabs onderin soms niets en kwam bij een tweede tik alleen het toetsenbord omhoog,
  tot de app opnieuw startte. Op de emulator bij 4 van de 8 pogingen, afhankelijk van de timing. Opgelost met
  `launchMode="singleTask"`: 0 van de 10 pogingen, en je komt na het koppelen terug waar je was. Staat op `main` voor de volgende versie.
  Nog zien op de echte telefoon: koppelen door een QR-code te scannen met de camera-app, en een link uit een andere app openen.
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
  `AudioPlaybackCapture` slaat telefonie over. Een Mac kan wel handsfree van een Android-telefoon zijn, maar weigert het spraakkanaal
  (getest op 2026-11-04, de uitkomst staat in `docs/PARITY.md`). Windows kan dat wel (Phone Link doet het via HFP), dus voor de pc is er een weg. Wat
  Tandem op de Mac wel kan, zonder dat geluid: de melding met naam en nummer, opnemen, ophangen, dempen en luidspreker aanzetten
  (`TelecomManager`, `ANSWER_PHONE_CALLS`) vanaf de Mac, en een venster als er gebeld wordt met die knoppen. Het verplaatsen van het
  geluid zelf werkt dan alleen met een andere weg: bellen via een VoIP-app (Wi-Fi bellen via Tandem, SIP) of de telefoon als
  Bluetooth-HID/HFP-apparaat, en die wegen zijn per stuk nog niet onderzocht.

  De knoppen "Naar hier verplaatsen" en "Call terugzetten" (0.2.76 tot 0.2.83) verplaatsten nooit geluid: ze zetten alleen een vlag. In 0.2.84
  zijn ze weg, het belkaartje is weer compact, en dempen werkt nu echt, op de telefoon zelf, vanaf elke computer.

  Opnieuw gevraagd op 2026-11-04, ook via Magisk of Shizuku: die route blijft ongebouwd om de reden bij "Bellen met het geluid op de computer".
  Wat nog kan: Linux als Bluetooth-headset van de telefoon (nog uit te zoeken, de laptop moet daarvoor aan staan), en voor de Mac een tweede
  Bluetooth-radio die macOS niet beheert: een USB-dongle aan een Linux-VM of een Raspberry Pi die het geluid over het netwerk naar de Mac stuurt
  (opzet staat in PhoneBridge, daar nog niet bevestigd). Niet rechtstreeks op de Mac: BTstack meldt daarbij kernel panics op macOS 26.0 tot 26.4.

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

## Shizuku/Magisk oplossing voor gespreksgeluid op Mac (2026-11-04)

Geparkeerd door Mark op 2026-11-04. Niet gebouwd, om de reden bij "Bellen met het geluid op de computer". `CallAudioService` (0.2.87 tot 0.2.90) las geen gespreksgeluid maar de gewone
microfoon, stopte nooit en is in 0.2.91 weg, samen met de knoppen. 0.2.92 en 0.2.93 brachten ze terug, met Shizuku-functies die alleen "gelukt" meldden; beide releases zijn niet afgekomen en in 0.2.94 is het weer weg.
Wat hieronder staat is de analyse van toen, geen plan.

Claude AI analyseerde waarom het spraakkanaal (SCO) niet werkt en of een Shizuku-oplossing haalbaar is.

### Het probleem
- **Mac weigert SCO**: `bluetoothd` rejecteert met `HOST_REJ_RESOURCES`, `connectSCO` en `transferAudioToComputer` geven `kIOReturnUnsupported` (0xE00002C7)
- **Android blokkeert**: apps krijgen geen toegang tot gespreksgeluid (`VOICE_CALL` alleen voor systeemapps)
- `IOBluetoothAddSCOAudioDevice` doet sinds macOS 10.9 niets meer; PhoneBridge vond dezelfde weigering op macOS 27

### De Shizuku-oplossing (werkingsprincipe)
1. **Rechten**: hulpproces met systeemrechten via Magisk of Shizuku (Shizuku werkt ook op normale Android, alleen even updaten)
2. **Stem van de ander**: proces leest gespreksgeluid uit en Tandem stuurt versleuteld naar Mac
3. **Op de Mac**: Tandem speelt die stem af + neemt eigen stem op met Mac-microfoon
4. **Jouw stem terug**: gaat naar telefoon, hulpproces zet het de gesprekslijn op; telefoon microfoon moet uit

### Risico's en onzekerheden
- **Stap 4 (stem terug)**: onzekerst - hangt af van Sony audiosoftware; werkt mogelijk niet op alle telefoons
- **Vertraging**: geluid gaat twee keer over netwerk = grotere vertraging, praten door elkaar
- **Echo**: Mac-speakers = echo terug, behalve met echo-onderdrukking
- **Alleen voor Shizuku/Magisk**: werkt niet op gewone Android telefoon (geen systeemrechten)
- **Elke Android-update kan het breken** (workaround op systeem-API's)

### Zekerheid schatting: 70-80% dat het in de praktijk werkt
- Afhankelijk van telefoonfabrikant (Sony is onzekerst)
- Niet een "eenduidige oplossing" maar workaround die redelijk betrouwbaar zou moeten zijn
- UI is verbeterd, alleen onderliggende techniek bouwen

### Wat te bouwen (techniek, geen UI)
- Shizuku hulpproces: gespreksgeluid uitlezen + terugzetzen naar gesprekslijn
- Mac kant: audio stream ontvangen + afspelen + microfoon opnemen + terugsturen
- Echo-onderdrukking op Mac
- Netwerk buffering voor vertraging
