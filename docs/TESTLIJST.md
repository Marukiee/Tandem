# Testlijst

Wat er veranderd is en wat Mark zelf nog op echte toestellen moet zien. De sessie die de bouwsessies aanstuurt houdt dit bij: elke
pull request levert zijn regels aan onder "Te testen door Mark", en die komen hier bij het samenvoegen. Vink af met `[x]`. De nummers
(R-B1, P2) verwijzen naar `docs/ROADMAP.md`. Een regel gaat weg zodra hij gezien is en de versie is uitgebracht.

## Ronde 1, 2026-11-14 (PR 4 tot 8, changelog 0.3.17, nog niet uitgebracht)

Wat veranderde: het deelvenster van Android is veilig en betrouwbaarder, apparaatnamen worden schoongemaakt, ontvangen bestanden
krijgen het downloadmerk, de kern houdt elke overdracht bij in een boek op schijf, en een rij kleine fouten is weg.

### Al gezien

- [x] **R-B1, Android**: een andere app kan Tandem niet meer laten sturen zonder tik. Gezien op de Oppo met de debug-app in een eigen
  testcircle: een bestandspad wordt geweigerd ("Nothing to send"), een onbekende aanroep geeft het venster met het apparaat
  aangevinkt en stuurt pas na een tik, en het echte deelmenu stuurt nog steeds meteen.
- [x] **R-B5, Mac**: de dev-app schrijft de sleutel in `control-state.json`. De knoppen zelf staan hieronder.

### Eerst dit

- [ ] **Een gewone overdracht tussen telefoon en Mac, beide kanten op.** Bij de proef van 2026-11-14 kwam een bestand van de debug-app
  niet aan op de nieuwe Mac Dev-app; de reden is niet uitgezocht. De kern is deze ronde verbouwd, dus dit is de belangrijkste test.
  Later nagekeken (PR 15): tussen `tandemd` en de Mac Dev-app komt een bestand beide kanten op goed aan, met de juiste
  controlesom. Van Android naar de Mac is niet opnieuw geprobeerd.

### Overdracht, tussen elk paar apparaten

- [ ] Veel bestanden tegelijk sturen (50 foto's): alles komt aan en de voortgang klopt.
- [ ] Naar twee apparaten sturen en bij een van de twee stoppen: de ander gaat door (R-B8).
- [ ] Een aanbod weigeren: de afzender ziet "Geweigerd" in plaats van een bestand dat blijft wachten (R-B14).
- [ ] De app halverwege een overdracht afsluiten en weer starten: niets gaat vanzelf verder (hervatten met een vraag komt in P2).
- [ ] Een aanbod dat op een ja wacht overleeft een herstart van de app en is daarna nog aan te nemen.

### Android

- [ ] Delen via het deelmenu naar een apparaat en naar "alle apparaten": gaat meteen, zonder extra tik.
- [ ] 60 foto's tegelijk delen: alle 60 komen aan (R-B7).
- [ ] Een bestand naar de Mac en naar Linux tegelijk (R-B6).
- [ ] De telefoon draaien tijdens het sturen: geen dubbele verzending, en het venster sluit als het klaar is (R-B17).
- [ ] Een foto met bijschrift delen: het klembord van de computer blijft zoals het was (R-B17).
- [ ] Tekst met opmaak delen uit een notitie-app: komt aan (R-B17).
- [ ] Een aanbod laten wachten: de melding met Aannemen en Weigeren verdwijnt na 10 minuten, of meteen bij aannemen in de app (R-B20).
- [ ] Op een toestel met Android 12, als er een is: een melding spiegelen crasht niet meer (R-B4).

### Mac

- [ ] Controlecentrum: Quick Share, Gedeelde muis, Scherm uitbreiden en Telefoon zoeken doen iets (R-B5).
- [ ] Een ontvangen bestand heeft het downloadmerk: `xattr -l <bestand>` toont `com.apple.quarantine`, en een ontvangen
  `.command` vraagt eerst of het mag starten (R-B3).
- [ ] Bestanden van een ander apparaat: een programma openen vraagt eerst "… openen?" (R-B3).
- [ ] Een overdracht die geweigerd, verlopen of geannuleerd is toont dat woord in het Nederlands op zijn rij.

### Linux

- [ ] Een bestand van de Mac over de rand slepen: het houdt zijn naam, geen "naam 2" (R-D1).
- [ ] "Sturen met Tandem" in Bestanden, Dolphin of Nemo toont de apparaten, ook met een bijzonder teken in de naam (R-B2).

### Windows

- [ ] "Sturen naar" toont de apparaten, ook de Mac met de gekrulde apostrof in zijn naam (R-B2).
- [ ] Vijf bestanden over de rand slepen: alle vijf hangen onder de aanwijzer (R-D2).
- [ ] Een ontvangen bestand: Eigenschappen zegt dat het van een andere computer komt. Een Office-bestand opent in beveiligde
  weergave; zeg het als dat hindert (R-B31).

## Ronde 2, 2026-11-14 (PR 10 tot 15, changelog 0.3.17)

Wat veranderde: de updatebanner duwt niets meer weg, de knop Zacht wekken is van de horlogekaart af, Android gaat zuiniger en netter
om met links, meldingen en bestanden, en op Linux en Windows zijn de pagina Bestanden, het neerzetten van bestanden, de updater en
"Versturen met Tandem" aangepakt. Niets hiervan draaide op een echte Linux- of Windows-computer: daar is alleen gebouwd en getest
met de tests in de code.

### Al gezien

- [x] **Updatebanner, Android** (emulator, per beeld gemeten): de pagina volgt de banner, de balk met tabbladen blijft staan, en na
  wegvegen staat alles weer op zijn plek.
- [x] **Updatebanner, Linux en Windows** (browser met proefgegevens): de onderste rijen van de zijbalk blijven staan.
- [x] **R-B19, Android** (Oppo, debug-app): een link met "openen" terwijl de app op de achtergrond staat komt als melding.
- [x] **R-V1, Android** (Oppo): in 10 minuten met het scherm uit 1 statusbericht, waar het er eerst 2 lege waren.
- [x] **Zacht wekken, Linux en Windows** (browser): de knop is van de pagina van het horloge af en de schakelaar staat er nog.

### Eerst dit

- [ ] **Android: tik op de melding van een Mac die je telefoon als tweede scherm wil, op de melding van Setup en op de
  batterijwidget.** De juiste pagina hoort nog te openen. Deze tikken lopen nu langs een nieuwe, afgesloten ingang (R-B25) en dat is
  alleen gebouwd, niet geprobeerd.
- [ ] **Linux: deksel dicht, vergrendelen en slaapstand.** Dit luistert nu naar het systeem in plaats van elke seconde te kijken
  (R-V5) en is nooit op een echte computer gedraaid. De regels staan onder Linux.

### Android

- [ ] Updatebanner: komt hij omlaag, dan schuift de pagina mee en blijft de balk onderaan staan. Na wegvegen staat de pagina weer
  waar hij stond.
- [ ] Open het scherm van een computer terwijl de banner er staat: de banner gaat weg.
- [ ] De kaart van het horloge, ook die van het horloge van een andere telefoon, heeft geen knop Zacht wekken meer. Aan en uit kan
  bij Instellingen, Gezondheid.
- [ ] Stuur vanaf een Mac een link met "openen" terwijl Tandem niet vooraan staat en het recht om over apps te tekenen uit is: er
  komt een melding, tikken opent de link, en de link staat op het klembord (R-B19).
- [ ] Open Tandem vanuit het startscherm en via een `tandem://pair` link: beide werken (R-B25).
- [ ] Stuur een bestand zonder naam, bijvoorbeeld een afbeelding uit een app die geen naam meegeeft: het komt aan met een extensie
  (R-B28).
- [ ] Weiger een aanbod via de melding: de melding verdwijnt meteen (R-B32).
- [ ] Stuur een groot bestand met het scherm uit: de overdracht loopt door (R-V3).
- [ ] Laat de telefoon een uur zonder horloge liggen: het horloge verbindt daarna nog binnen een paar seconden (R-V4).
- [ ] Gedeelde muis naar de telefoon: de aanwijzer en de tik vallen samen, ook onderaan het scherm en na draaien (R-B21).

### Mac

- [ ] De kaart Gezondheid op de pagina van het horloge heeft geen knop Zacht wekken meer en geen lege ruimte waar hij stond. De
  schakelaar staat op dezelfde pagina onder "Terwijl je slaapt".

### Linux

- [ ] Bijwerken vanuit een oude AppImage: de update komt binnen, de app herstart en de nieuwe versie draait. In
  `~/.cache/nl.markmaaktmedia.tandem` staat een map `Tandem-update-...` met rechten `drwx------` (R-B10).
- [ ] Bestanden: kies een map op de telefoon en druk Download: de map komt met alles erin in de downloadmap (R-B18).
- [ ] Bestanden: open een `.txt`: hij opent meteen. Open een `.sh` of `.desktop`: eerst de vraag, pas na Openen gaat hij open.
- [ ] Bestanden: sleep een bestand en een map op een open map van de telefoon: ze staan er daarna in, een bestaande naam krijgt (2).
- [ ] Sleep een bestand op de tweede rij in de zijbalk, ook met schaal 150 of 200 procent: het gaat naar dat apparaat en niet naar
  het gekozen apparaat. Op een apparaat dat weg is: een melding dat er niets gestuurd is (R-D11).
- [ ] Laat een bestand los op de pagina Gedeeld: een melding "Laat de bestanden op een apparaat los".
- [ ] Een sms met `a < b & c` als melding: de tekst staat er precies zo (R-B24).
- [ ] Herstart Tandem en gebruik daarna in Dolphin, Nemo en Bestanden, zonder die te herstarten, "Versturen met Tandem" naar een
  apparaat: het gaat meteen weg. `tandem --send --to <id> <bestand>` vanuit een terminal: de kaart vraagt eerst, met dat apparaat
  bovenaan (R-B29).
- [ ] Deksel dicht terwijl een ander de muis heeft: de muis komt meteen terug. Scherm vergrendelen en ontgrendelen: de andere
  apparaten zien vergrendeld en weer open. Slaapstand en weer wakker: de apparaten verbinden binnen een paar seconden weer (R-V5).
- [ ] `top -H -p $(pidof tandem)`: de draad `tandem-lid` wordt niet meer elke seconde wakker.
- [ ] Zijbalk: komt er een update, dan blijven Gedeeld tot en met Instellingen op hun plek staan.

### Windows

- [ ] Bijwerken: de installer start en Tandem komt in de nieuwe versie terug (R-B10).
- [ ] Bestanden: een map downloaden, een `.exe` openen (eerst de vraag van Tandem, daarna die van Windows), uploaden met de knop en
  door slepen (R-B18).
- [ ] Sleep een bestand op een andere rij in de zijbalk, ook met schaal 150 procent: het gaat naar dat apparaat (R-D11).
- [ ] Herstart Tandem en gebruik "Kopiëren naar" en dan "<apparaat> (Tandem)": het gaat meteen weg (R-B29).
- [ ] Scherm vergrendelen terwijl een ander de muis heeft: de muis komt terug.

### Hervatten en vrije ruimte (P2, P5, PR 15)

Wat veranderde: een overdracht die halverwege stopte door een herstart gaat nooit vanzelf verder. De Mac vraagt "Verder gaan of
weggooien" in een melding en bovenaan Gedeeld. Een ontvanger weigert vooraf wat niet past, en de Mac vraagt het voor het sturen. Op
Android en op Linux en Windows is alleen de kern nieuw; hun schermen volgen in ronde 3.

- [x] **Gezien op de Mac Dev-app** met `tandemd` als afzender en een bestand van 3 GB: gestopt bij 1,0 GB en weer gestart staat de
  rij er met hoe ver het was. Verder gaan ging door vanaf 1,0 GB en het bestand kwam goed aan. Weggooien haalde het halve bestand weg
  en de afzender sloot het af als geannuleerd. Met de afzender weg is Verder gaan grijs. De knoppen zijn aangeroepen via een
  proefinstelling, niet met de muis; de melding zelf en de vraag over ruimte zijn niet gezien.
- [ ] Mac: stuur vanaf de telefoon een groot bestand (een paar GB) en sluit Tandem op de Mac halverwege. Start Tandem weer: er komt
  een melding "Een overdracht van <telefoon> stopte halverwege" met Verder gaan en Weggooien, en bovenaan Gedeeld staat de rij.
- [ ] Mac: klik Verder gaan: het gaat verder vanaf waar het was (de balk begint niet bij 0) en het bestand opent goed.
- [ ] Mac: herhaal en klik Weggooien: de rij gaat weg, er komt niets in Downloads, en de telefoon toont de overdracht als
  geannuleerd.
- [ ] Mac: herhaal met Tandem op de telefoon dicht: Verder gaan is gedimd, en een klik zegt dat de telefoon niet verbonden is.
- [ ] Mac: stuur iets dat groter is dan de vrije ruimte van de telefoon: de Mac vraagt eerst "Niet genoeg ruimte op <telefoon>" met
  Annuleer en Toch sturen. Bij Toch sturen weigert de telefoon en staat er "Niet genoeg ruimte op <telefoon>".
- [ ] Android, Linux en Windows: een gewone overdracht van en naar de Mac werkt nog, beide kanten op.
- [ ] Overal: stop een aanbod bij de afzender voordat het is aangenomen: de ontvanger toont het als geannuleerd.

## Ronde 3, 2026-11-14 (PR 17 en 18, changelog 0.3.18; de rest van ronde 3 loopt nog)

Wat veranderde: de Mac onthoudt de maat van je andere schermen, de updater van de Mac weigert wat hij niet kan controleren, een map
sturen blokkeert het venster niet meer, en Android vraagt nu ook "Verder gaan of weggooien" en waarschuwt bij te weinig ruimte.

### Al gezien

- [x] **R-B35, Mac** (Dev-app met `tandemd`): eerst "Size not known yet"; na een maat van het andere apparaat en een herstart staat
  de maat er nog.
- [x] **P2, Android** (Oppo, debug-app, 1,5 GB): geforceerd gestopt bij 410 MB en weer gestart staat de rij er. Verder gaan ging door
  vanaf 410 MB. Met de afzender weg is de rij grijs en zegt een tik waarom. Weggooien haalde de rij en het halve bestand weg.
- [x] **P5, Android** (Oppo): een bestand van 100 MB naar een apparaat met 20 MB vrij vraagt eerst. Annuleren stuurt niets, Toch
  sturen geeft "Niet genoeg ruimte".

### Eerst dit

- [ ] **Mac: de eerstvolgende update installeren via de knop in de app.** De updater weigert nu alles wat hij niet kan controleren
  (R-B11). De app in `/Applications` heeft de sleutel en een gewone versie, dus een echte release hoort door te gaan, maar het pad
  zelf is niet gedraaid. Lukt het niet, dan moet de nieuwe versie een keer met de hand geinstalleerd worden.

### Mac

- [ ] Open Instellingen, Muis en toetsenbord nadat een ander apparaat zijn maat eens heeft gezegd, en herstart Tandem terwijl dat
  apparaat uit staat: het scherm toont nog zijn maat in plaats van "Size not known yet" (R-B35).
- [ ] Haal een apparaat uit de circle en koppel het weer: tot het iets zegt staat er weer "Size not known yet".
- [ ] Stuur een grote map naar de telefoon: het venster blijft bewegen tijdens het inpakken, en na afloop staat er geen map
  `tandem-...` meer in de tijdelijke map (R-B16).
- [ ] Stuur twee bestanden met dezelfde naam uit verschillende mappen en druk in de lijst op Openen of Tonen: elk opent zijn eigen
  bestand.
- [ ] Stuur via het Deel-menu een bestand naar Tandem zonder apparaat te kiezen: het menu noemt eerst het bestand en de map, dan de
  apparaten (R-B23).
- [ ] Zet "wakker blijven met de klep dicht" aan op een Mac zonder de regel: een keer het wachtwoord, daarna werkt het.
  `sudo cat /etc/sudoers.d/tandem-pmset` toont de regel (R-B26).

### Android

- [ ] Stuur een groot bestand van de Mac naar de telefoon, forceer Tandem halverwege dicht (Instellingen, Apps, Tandem, Forceer
  stop) en open het opnieuw: op het tabblad Gedeeld staat bovenaan een rij "Halverwege gestopt ..." met Weggooien en Verder gaan, en
  er komt een melding met dezelfde twee knoppen.
- [ ] Tik Verder gaan met de Mac aan: de rij verdwijnt en het bestand loopt door vanaf waar het was, en komt heel aan.
- [ ] Doe het nog eens en tik Weggooien: de rij verdwijnt en er komt geen rij "Geannuleerd" bij.
- [ ] Doe het nog eens met de Mac uit: de rij is grijs en zegt dat de Mac niet verbonden is. Zet de Mac aan: de rij wordt vanzelf
  weer actief, maar het bestand gaat pas verder als jij op Verder gaan tikt.
- [ ] Tik de knoppen in de melding zelf, en tik op de melding: de app opent op Gedeeld. Dit is alleen gebouwd, niet geprobeerd.
- [ ] Kies bij een apparaat een bestand dat groter is dan de vrije ruimte van de ontvanger: "Niet genoeg ruimte op ..." met
  Annuleren en Toch sturen. Probeer het ook via het deelmenu van Android en via een snelkoppeling naar een apparaat; die twee paden
  zijn alleen gebouwd.

### Slepen op de Mac (PR 21)

Alleen gebouwd en met tests van de logica gecontroleerd: er was geen tweede computer. Dit kun alleen jij beoordelen.

- [ ] Een foto uit Foto's over de rand naar de andere computer slepen: daar komt het originele bestand met zijn naam aan, geen naamloze png.
- [ ] Een video of pdf uit Mail of Foto's over de rand slepen: hij gaat mee.
- [ ] Een plaatje uit Safari over de rand slepen: er komt een plaatje aan, geen webadres als tekst.
- [ ] Een bestand over de rand slepen en de knop op de andere computer loslaten voor het aankomt: er blijft op de Mac niets hangen en de muis werkt gewoon.
- [ ] Vanaf Linux of Windows vijf foto's naar de Mac slepen en met de knop vast loslaten in Finder: alle vijf komen waar je losliet, niet een deel op het bureaublad.
- [ ] Een groot bestand naar de Mac slepen, de knop loslaten en verder werken: na een paar seconden staat het als kaartje naast de aanwijzer, klikken en scrollen werken gewoon, en je kunt het vanaf het kaartje ergens neerzetten.
- [ ] Met een bestand onder de aanwijzer scrollen: het venster eronder scrolt.
- [ ] Een weblink uit Safari op een apparaat in het venster of het menubalkpaneel laten vallen: hij komt als link aan, zonder foutmelding.
- [ ] Een foto uit Foto's op een apparaat in het venster laten vallen: hij wordt gestuurd.
- [ ] Een bestand op het Tandem-icoon in het Dock laten vallen: er komt een menu met het bestand en de apparaten; dubbelklikken op bestanden opent ze nog steeds in hun eigen app.
- [ ] Een ontvangen bestand uit de lijst op een apparaatpagina naar Finder slepen: het komt daar terecht.
- [ ] Een computer toestaan de muis van de Mac te gebruiken (Instellingen › Muis en toetsenbord): macOS vraagt meteen of Tandem Finder mag bedienen, niet midden in een sleep.
- [ ] Na een tijd slepen het logboek van de aanwijzer bekijken: staan er regels "slow mouse event", dan hapert de muis door Tandem (R-D17).

### Linux en Windows: hervatten, vrije ruimte en de schermmaat (PR 22)

Bekeken in een browser met proefgegevens, in het Engels en Nederlands. Niets draaide op een echte Linux- of Windows-computer.

**Linux**
- [ ] Start Tandem terwijl een overdracht van je telefoon halverwege stopte (zet de telefoon uit tijdens een groot bestand en herstart Tandem): er komt een melding met Verder gaan en Weggooien en op Gedeeld staat bovenaan een rij. Verder gaan haalt de rest op, Weggooien haalt de rij weg zonder foutmelding.
- [ ] Zet de telefoon uit en kijk naar de rij: Verder gaan is grijs en een klik zegt dat de telefoon niet verbonden is.
- [ ] Stuur een bestand dat groter is dan de vrije ruimte op de telefoon: je krijgt de vraag Annuleer / Toch sturen. Annuleer stuurt niets.
- [ ] Sleep een groot bestand naar de telefoon: geen vraag.

**Windows**
- [ ] Zelfde als bij Linux, maar de melding heeft geen knoppen: de keuze staat op de pagina Gedeeld.
- [ ] Controleer dat de telefoon bij een vol Downloads-schijf het bestand weigert met "Niet genoeg ruimte op dit apparaat".

**Beide**
- [ ] Open Schermen indelen, sluit en start Tandem opnieuw: de formaten van de andere schermen staan er meteen weer.

## 0.3.19, 2026-11-14 (PR 19, 24, 25 en 26)

### Wachtrij, paneel en vaste redenen (PR 19)

De wachtrij en het paneel zijn gezien met de Mac Dev-app en `tandemd`. De wachtrij staat alleen op de Mac aan; Android en Linux en
Windows houden hun oude gedrag tot hun schermen er zijn.

- [ ] **Mac**: stuur een bestand naar een apparaat dat uit staat. Er hoort "Wacht op ..." te komen, en bovenaan Gedeeld onder Onderweg een rij met een klok.
- [ ] **Mac**: zet dat apparaat aan. Het bestand hoort vanzelf te gaan en heel aan te komen; de rij gaat weg.
- [ ] **Mac**: stuur iets naar een apparaat dat uit staat, verander het bestand, zet het apparaat aan. Er hoort "Veranderd sinds het gestuurd werd" te staan en het bestand komt niet aan.
- [ ] **Mac**: druk op het kruisje van een rij in de wachtrij. De rij gaat weg en er komt later niets aan.
- [ ] **Mac**: zet automatisch accepteren uit voor een apparaat en stuur vanaf daar iets. Onder Inkomend horen Weigeren en Accepteer te staan, en die horen te werken.
- [ ] **Mac**: stuur een map naar een apparaat dat uit staat en zet het daarna aan. De zip hoort aan te komen.
- [ ] **Mac**: stuur iets naar een apparaat dat om een ja vraagt en uit staat, en zet het aan. De rij hoort van "Wacht op ..." naar "Wacht op een ja van ..." te gaan.
- [ ] **Telefoon en Linux/Windows**: stuur iets naar een apparaat dat uit staat. Er hoort nog steeds te staan dat het offline was en het niet kreeg, en het komt later ook niet aan (de wachtrij staat daar nog uit).

### Windows: het downloadmerk alleen op wat iets kan starten (PR 25)

**Windows**
- [ ] Stuur een Word- of Excel-bestand vanaf je telefoon naar de pc en open het: het opent normaal, zonder beveiligde weergave.
- [ ] Stuur een `.exe` of `.bat` vanaf je telefoon: Windows vraagt nog steeds eerst (SmartScreen / "Onbekende uitgever") voordat het start.
- [ ] Haal hetzelfde Word-bestand op via de pagina Bestanden en door het vanaf de telefoon naar het venster te slepen: ook zonder beveiligde weergave.

**Linux**
- [ ] Niets nieuws te testen; ontvangen bestanden openen zoals eerst.

### Linux en Windows: het nieuwe ontwerp (PR 24)

Alleen het venster van Linux en Windows; de Mac volgt in een eigen ronde. Nog niet op een echte Linux- of Windows-computer gezien.

- [ ] Maak het venster smal en weer breed: de zijbalk klapt in en uit. Loopt dat soepel?
- [ ] Open Instellingen: de zijbalk wordt smal. Staat alles waar je het zoekt in de zeven onderwerpen?
- [ ] Speel muziek in twee apps op de telefoon: komen er twee tabs, en wisselt de kaart?
- [ ] Gedeeld op een breed venster: klik een bestand aan en kijk of het voorbeeld ernaast klopt. Dubbelklik opent het.
- [ ] De drie puntjes bij een bestand: Openen, Toon in map, Uit lijst halen.
- [ ] Wijs een ronde knop aan: staat de naam er meteen onder?
- [ ] Een update: staat de melding in de smalle zijbalk als icoon, en klapt hij open als je erop klikt?

### Codes uit een sms (PR 26, R-B37)

Gezien op emulators met Android 15 en 16 en `tandemd` als ontvanger, nog niet op een echte telefoon of Mac. De Mac en de telefoon
moeten allebei op 0.3.19 staan.

- [ ] Zet Berichten onder Instellingen, Meldingen, Apps uit en stuur jezelf een sms met een code: de code staat op de computer op het klembord, en je krijgt alleen "code gekopieerd", geen melding van het bericht.
- [ ] Zet "Meldingen naar je apparaten sturen" uit en stuur nog een code: ook die komt aan en wordt gekopieerd, andere meldingen niet.
- [ ] Start de Tandem-app op de telefoon opnieuw (of wacht tot Android hem stopt) en stuur direct daarna een code: komt hij aan?
- [ ] Kijk bij Instellingen, Meldingen, rij Codes: zonder Shizuku staat er dat chatberichten (RCS) en achtergehouden berichten gemist worden; met Shizuku staat er de gewone tekst.
- [ ] Krijg je de code via een chatbericht (RCS) in Google Berichten en niet als sms? Dan komt hij zonder Shizuku niet aan, met Shizuku wel: probeer dat apart.
- [ ] Komt er daarna nog een code niet aan? Zeg welke telefoon, welke berichten-app, en of het een sms of een chatbericht was.

## 0.3.20, 2026-11-15 (PR 27 tot 30 en 34)

### Wachtrij, paneel en redenen op Android (PR 27)

Gezien op een emulator met `tandemd` als ander apparaat, nog niet op een echte telefoon.

- [ ] Zet de Mac of een ander apparaat uit, kies op de telefoon bij dat apparaat Bestanden versturen: je ziet "Wacht op ...", en onder Gedeeld een rij Onderweg met "Niet sturen".
- [ ] Start het apparaat weer: het bestand komt aan zonder iets te doen, ook na een herstart van Tandem op de telefoon.
- [ ] Tik bij zo'n rij op het kruisje: de rij verdwijnt en er komt niets aan.
- [ ] Stuur vanaf de Mac een bestand naar de telefoon dat om een ja vraagt: je ziet het onder Inkomend met Weigeren en Accepteer.
- [ ] Zet de telefoon op Nederlands en kijk of alle regels in het paneel kloppen, ook de redenen bij een mislukt bestand.

### Wachtrij, paneel en redenen op Linux en Windows (PR 28)

Gebouwd en in de proefweergave gezien, nog niet op een echte Linux- of Windows-computer.

- [ ] Stuur een bestand naar een apparaat dat uit staat (sleep het op de kaart, of "Bestanden versturen"): het staat onder Onderweg met "Wacht op ...", en gaat zodra het apparaat terug is.
- [ ] Zet het apparaat weer aan: de rij wordt een lopende overdracht en verdwijnt als hij klaar is.
- [ ] "Niet sturen" op een wachtende rij: de rij is weg en er komt niets aan.
- [ ] Een bestand dat onderweg mislukt (haal het wifi weg): rij met de reden in woorden en "Opnieuw sturen"; "Verberg" haalt hem weg.
- [ ] Een overdracht die halverwege stopte gaat niet vanzelf verder, ook niet na het opstarten van Tandem.
- [ ] Een inkomend aanbod staat onder Inkomend met Accepteren en Weigeren.
- [ ] "Verzenden met Tandem" in de bestandsbeheerder: een apparaat dat uit staat is kiesbaar en het bericht zegt dat het wacht.

### Android: slepen in en uit de app (PR 30)

Neerzetten op een apparaatkaart is gezien op een emulator. Dat een andere app een bestand uit Tandem echt aanneemt is niet tot het
eind gezien: de apps op de emulator weigerden het.

- [ ] Sleep een foto of bestand uit een andere app (Foto's of Bestanden, met twee vensters naast elkaar) op de kaart van een apparaat dat aan staat: de kaart kleurt, het bestand komt aan en staat onder Gedeeld.
- [ ] Hetzelfde op een apparaat dat uit staat (zodra #27 erin zit): het wacht en gaat zodra het terug is.
- [ ] Sleep tekst of een link op de kaart: er gebeurt niets en de andere app laat zien dat het terugkwam.
- [ ] Een ontvangen bestand op de pagina van een apparaat lang indrukken en naar een andere app slepen (berichten, mail): de andere app krijgt het bestand.
- [ ] Op Gedeeld: lang indrukken kiest de rij, daarna dezelfde rij weer lang indrukken en slepen neemt alles mee wat gekozen is.

### Mac: de updatekaart en een rij Berichten (PR 29)

Gezien in de Dev-app als momentopname, niet op echt glas.

- [ ] Zijbalk van het hoofdvenster als er een update is: staan "Versie ..." en "Je hebt ..." er helemaal, en staan Later en Bijwerken heel op hun eigen regel?
- [ ] Menubalkpaneel als er een update is: past alles op een regel zonder afgekapte tekst?
- [ ] Met twee telefoons: staat er nog maar een rij Berichten, met het aantal ongelezen van beide samen?
- [ ] Open Berichten: staat boven de lijst een knop met de naam van de telefoon, en wissel je daarmee naar de andere telefoon?
- [ ] Wissel heen en weer: opent elke telefoon zijn eigen gesprekken, en onthoudt de rij Berichten welke telefoon je het laatst open had?
- [ ] Zet een telefoon uit: kun je via de knop nog naar de andere?

### De maandkalender en de stappentegel (PR 34)

Op Android, de Mac en Linux en Windows. Gezien op een emulator, in de Dev-app en in de proefweergave.

- [ ] Telefoon, start van de dag zonder stappen: staat de stappentegel met 0 naast de slaap op het startscherm?
- [ ] Gezondheid, Hart, Kalender: een paar maanden terug bladeren (ook van oktober naar september), geen crash.
- [ ] Zie je in één oogopslag welke dagen hoger (warm) en lager (koel) waren dan normaal?
- [ ] Tik op een dag: groot getal, datum en "hoger/lager dan normaal" erboven; nog eens tikken gaat terug.
- [ ] Slaap en Activiteit: kleur sterker naarmate je dichter bij 7 uur of je stappendoel zat, vet bij doel gehaald.
- [ ] Mac en Linux/Windows: dezelfde kalender, klikken en bladeren voelen goed, licht en donker leesbaar.

## 0.3.21, 2026-11-15 (PR 32, 33, 35, 37 en 38)

### Bestanden en Quick Share in de kern (PR 32)

Getest in de kern, niet op een echt toestel.

- [ ] Bestanden: een groot bestand van de telefoon naar de Mac halen, halverwege de wifi uit, het bestand op de telefoon veranderen, opnieuw halen: het nieuwe bestand komt heel aan.
- [ ] Zelfde zonder te veranderen: hij gaat verder waar hij was.
- [ ] Quick Share van de telefoon naar de Mac, halverwege annuleren op de telefoon: in Downloads staat niets half.
- [ ] Quick Share gewoon: bestanden komen aan met hun eigen naam.

### Kern en tandemd: dode instelling weg, te lang pad geweigerd, vastere tests (PR 33)

- [ ] Niets te testen: je ziet er niets van.

### Vrije ruimte op de juiste schijf (PR 35)

- [ ] Zet op de Mac de downloadmap op een andere schijf (bijvoorbeeld een externe SSD) en stuur vanaf een telefoon een bestand dat groter is dan de vrije ruimte van de Mac, maar kleiner dan die van de systeemschijf: de telefoon moet vragen of hij toch wil sturen.
- [ ] Haal de externe schijf eruit en stuur weer iets: het moet niet meer geweigerd worden om ruimte.

### Mac: slepen zonder kopie in Downloads, Controlecentrum zonder rondvragen (PR 37)

Gebouwd en in de kern getest; het slepen zelf is niet tussen twee echte computers gezien.

- [ ] Sleep een of meer bestanden vanaf Linux of Windows over de rand naar de Mac en laat ze los op het bureaublad en in een Finder-venster: de bestanden moeten daar komen en er mag geen kopie in Downloads blijven.
- [ ] Sleep twee bestanden met dezelfde naam tegelijk: allebei moeten aankomen.
- [ ] Zet Control Center-knoppen en de batterijwidget aan en koppel een telefoon los of aan: ze moeten nog steeds bijwerken.
- [ ] Gebruik de gedeelde muis een dag en kijk dan met `grep "slow mouse event" ~/Library/Application\ Support/Tandem/pointer-share.log` of er trage regels in staan (voor R-D17).

### Linux en Windows: vergrendeling uit de waarnemer (PR 38)

- [ ] Op Linux: sluit het scherm (vergrendelen) en kijk of de Mac zijn muis niet meer naar de Linux-pc laat gaan, en weer wel na het ontgrendelen (zoals voor deze wijziging).
- [ ] Verder niets te zien; het scheelt vooral rondvragen bij logind.

## 0.3.22, 2026-11-15 (PR 44 en 45)

Alleen gebouwd, niet in de echte app gezien.

- [ ] Als er een update klaarstaat: staat in de zijbalk "Versie ..." en "Je hebt ..." er helemaal, met Later en Bijwerken op een eigen regel eronder? Hetzelfde in het menubalkpaneel. (Je ziet dit pas bij de update na 0.3.22: de kaart wordt getekend door de versie die je draait.)
- [ ] Stuur een bestand vanaf de Mac en open het menubalkpaneel: tijdens het sturen de balk, daarna kort "Verstuurd" met een vinkje en zonder balk, en na tien seconden is de regel weg.
- [ ] Open het paneel een paar minuten later: er staat geen oude regel meer. Een ontvangen bestand blijft een minuut staan.

## 0.3.23, 2026-11-15 (PR 39, 40, 41, 42, 43 en 46)

Wat veranderde: de telefoon doet op mobiele data met het scherm uit veel minder werk (R-V15), Instellingen heeft op Linux, Windows en
Android een deel Apparaten waar je per apparaat kiest wat het mag (Circle, P6), een apparaatnaam geldt voor de hele circle (R-I33) en
de kern kan mappen als mappen ontvangen (P4) en een apparaat een bestand laten sturen naar een derde (P9). Van de laatste drie, en
van de naam, is nog niets te zien: de schermen op de Mac wachten op PR 31. Alleen gebouwd en getest in CI en met de kerntests, niet op
een echt toestel gezien.

- [ ] **Accu onderweg (R-V15).** Een uur op mobiele data met het scherm uit, via Tailscale, en daarna het batterijverbruik van Tandem
  vergelijken met eerder (was ongeveer 11% in 4 uur). Dit is de belangrijkste test van deze versie.
- [ ] Onderweg: komen meldingen en een code uit een sms nog meteen op de Mac aan?
- [ ] Laat de telefoon overgaan vanaf de Mac: gaat hij nog meteen?
- [ ] Een laptop die een paar dagen uit stond: opent hij weer en is hij binnen enkele seconden verbonden? En een telefoon waar Tailscale
  een weekend uit stond: verbindt hij weer als je Tailscale aanzet?
- [ ] Optioneel: `adb logcat -s Tandem | grep "dialling a device that is away"` en tellen hoeveel pogingen er in een uur zijn.
- [ ] **Circle op Linux of Windows (PR 41).** Instellingen › Apparaten. Klopt de zin bovenaan met wat je ziet? Tik in "Bedient deze
  computer" op je telefoon: het rondje wordt doorgestreept; de muis van de telefoon werkt dan niet en de telefoon krijgt de melding dat
  het niet mag. Zet het daarna weer aan.
- [ ] Details: kies Beperkt en kijk of de schakelaars meegaan. Apparaat toevoegen en Verwijderen openen de vensters die je al kent.
- [ ] Een apparaat dat de standaard volgt maar waar in de standaard een schakelaar uit staat: er staat een regel "Volgt de standaard voor
  alle apparaten in Instellingen › Delen, waar ... uit staat".
- [ ] **Circle op Android (PR 43).** Instellingen › Apparaten (bovenaan). Klopt de zin met wat je ziet? Tik in "Bedient deze telefoon" op
  je Mac: doorgestreept; probeer dan vanaf de Mac de telefoon te vergrendelen of te bedienen: dat moet niet gaan. Zet het weer aan.
- [ ] Tik op een apparaat in de lijst, kies Beperkt en kijk of de schakelaars meegaan; kies weer Eigen apparaat. Donker en licht, en de
  taal op Nederlands. Dezelfde regel over de standaard staat er, met "Instellingen › Bestandstoegang". Niet op de emulator bekeken.
- [ ] **Naam (R-I33, PR 46).** Nog niets om aan te tikken. Wel: de naam van je Mac en telefoon is na het bijwerken nog hetzelfde, ook
  na een herstart van de apps.
- [ ] **Mappen (P4, PR 39) en sturen via een ander apparaat (P9, PR 40).** Nog niets op een toestel: de Mac zet dit pas aan in de volgende
  PR's (na PR 31). Wel: gewone bestanden en mappen (die gaan nog als zip) sturen tussen Mac en telefoon werkt zoals altijd.
