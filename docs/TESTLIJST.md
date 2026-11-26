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

## 0.3.24, 2026-11-17 (PR 47 en 48)

Wat veranderde: de horlogekaart toont altijd een slaaptegel naast de stappen, ook zonder gegevens van die nacht (PR 47, alle platforms), en
de kern houdt nu een verkeerslog bij op schijf om te vinden wat de telefoon wakker houdt (PR 48, R-V15). Gebouwd en door CI getest, niet
op een telefoon gedraaid: dat kost een echte nacht. Het log zelf kost geen batterij bedoeld: geen timer, alarm of wake lock, het schrijft
alleen als de engine toch wakker is; of dat klopt moet de meting laten zien.

- [ ] Een nacht met het horloge aan de lader: toont de kaart op het beginscherm van de telefoon nu naast de stappen een tegel "Geen gegevens" voor de slaap, en opent een tik erop de slaappagina?
- [ ] Hetzelfde op de Mac (horlogekaart) en in de Linux- of Windows-app.
- [ ] Na een nacht met gegevens: staat de gewone slaaptegel er weer en is niets veranderd?
- [ ] Open Instellingen > Over > Verkeerslog op de telefoon na een uur gebruik: staan er apparaten, soorten berichten en uren in?
- [ ] Na een nacht van de lader: `adb pull /sdcard/Android/data/nl.markmaaktmedia.tandem/files/traffic` en kijk of er regels in staan met `ctx.screen_on:false` en wat erin zit.
- [ ] Kost de log zelf batterij? Vergelijk een nacht met en zonder (de meetsessie doet dit: geen nieuwe `*alarm*` of `*job*` wake locks voor Tandem, hetzelfde aantal kernel wake-ups).
- [ ] Op de Mac en in de Linux- of Windows-app: opent "Verkeerslog openen" de map met de dagbestanden? (Mac, Linux en Windows geven scherm, netwerk en batterij nog niet door, alleen stil en data-kosten.)

## 0.3.26, 2026-11-18 (PR 50, horloge punt 5)

Wat veranderde: bedtime mode op het horloge pauzeerde de muziek meteen. Nu begint de nachtopname (en, met "Stille slaap" aan, de stille
stand van de telefoon) bij bedtime mode, maar muziek en computers (pauzeren en vergrendelen, als die schakelaars aan staan) pas als het horloge
je echt ziet inslapen: 25 minuten stil, zoals de nachtdetectie al deed. Wordt je 's nachts wakker en val je weer in slaap, dan pauzeert het opnieuw.
Een nacht die je met de hand op het horloge start, telt nog steeds als slapen vanaf het begin.

- [ ] **Bedtime mode aan met muziek.** Zet 's avonds bedtime mode aan terwijl er muziek speelt (telefoon, Sonos of een computer, met "Muziek
  pauzeren" aan): de muziek speelt gewoon door en de telefoon wordt wel stil als "Stille slaap" aan staat.
- [ ] Blijf ongeveer 25 tot 30 minuten stil liggen: nu pauzeert de muziek, en met "Computers vergrendelen" aan gaan de computers op slot. Kijk op de
  telefoon met `adb logcat -s TandemWatch | grep "Sleep state"`, op het horloge `adb logcat -s TandemWatch | grep Asleep`.
- [ ] Word 's nachts wakker, zet muziek aan en val weer in slaap: na een kwartier tot een half uur stil pauzeert die muziek ook.
- [ ] 's Ochtends bedtime mode uit: de telefoon is weer normaal, er wordt niets dubbel gepauzeerd en de ochtendvraag over muziek die doorspeelde (alleen als
  "Muziek pauzeren" uit staat) komt hooguit één keer.
- [ ] Een nacht met de hand starten op het horloge: de muziek pauzeert meteen, zoals vóór deze versie. Is dat niet wat je wilt, zeg het: dan wacht hij daar ook op de detector.
- [ ] Het horloge in bedtime mode zonder ooit stil te liggen (de hele avond bezig): er wordt niets gepauzeerd en 's ochtends staat de opname er gewoon.

## 0.3.25, 2026-11-18 (PR 49, R-V18)

Wat veranderde: het Pixel Watch is binnen een dag leeg, dus is wat het horloge wakker houdt aangepakt (zie R-V18 in
`docs/ROADMAP.md`). De telefoon stuurt het horloge geen meldingen, spelers, apparaatlijsten en batterijen meer die niets nieuws
zeggen, en geen verwijdering van iets wat het horloge nooit kreeg. Live hartslag meet hooguit 15 minuten achter elkaar, schrijft de
sensor per 2 seconden weg en stopt bij uitzetten. Een gewone hartslagmeting stopt na 2 minuten als hij niets vindt, en meteen
bij horloge af of aan de lader.
Een verbinding die steeds wegvalt zoekt minder vaak snel. De Mac en de Windows-app vragen alleen live hartslag zolang hun venster
zichtbaar is. Gemeten op een emulator (tellingen, geen mA) en met eenheidstests, niet op een echt horloge: dat is jouw dag.

- [ ] **Een dag om de pols.** Doe het horloge 's ochtends op volle lading om en noteer het percentage 's avonds. Eerder was het leeg
  binnen een dag. Vergelijk met een dag zonder Tandem op het horloge als je kunt.
- [ ] Meldingen: een app-melding komt aan en trilt, antwoorden werkt, weghalen op de telefoon haalt hem weg op het horloge en omgekeerd.
  Dezelfde melding die de app opnieuw plaatst (een chat die verandert) komt wel aan zodra de tekst anders is.
- [ ] Een download met voortgangsbalk: de balk op het horloge loopt in stappen van 10 procent mee en staat aan het eind op klaar.
- [ ] Bellen: scherm gaat aan, trillen, opnemen, ophangen, weigeren, zoals altijd.
- [ ] Muziek: afspelen, pauzeren, volgend nummer, de tijdbalk springt mee als je in het nummer spoelt, en de titel staat goed na een pauze van een uur.
- [ ] Slaap: een nacht met het horloge om, 's ochtends staat de nacht op de slaaptegel. De nachtopname zelf is niet aangepast, alleen
  het stoppen van een hartslagmeting bij de lader en bij horloge af. Klopt de slaaptijd en de hartslag met een nacht eerder?
- [ ] Stappen en hartslag overdag: de tegels op de telefoon lopen bij als vanouds.
- [ ] Live hartslag op de Mac: open de horlogekaart, het getal beweegt. Zet het venster achter een ander venster of minimaliseer het: na
  ongeveer een halve minuut meet het horloge niet meer (`adb logcat -s TandemWatchLiveHeart` op het horloge meldt "Live stopped"). Venster terug: het getal komt terug.
- [ ] Hetzelfde in de Windows-app (Tauri): venster minimaliseren stopt het vragen.
- [ ] Een verbinding die wegvalt (telefoon buiten bereik en terug, of Bluetooth uit en aan): de eerste keer vindt het horloge de telefoon binnen
  een halve minuut weer. Valt de verbinding binnen 10 minuten nog eens weg, dan zoekt het horloge die tweede keer rustiger (gebalanceerd in
  plaats van een minuut op volle sterkte) en kan het terugvinden enkele tientallen seconden langer duren. Dat is de bedoeling; duurt het
  langer dan een minuut of twee, meld het.
- [ ] **De grens van 15 minuten.** Kijk 20 minuten achter elkaar naar de hartslag op een zichtbaar Mac- of Windows-venster: na 15 minuten
  verdwijnt het live getal en de tegel valt terug op de gewone hartslag, 30 minuten lang. Het venster sluiten en weer openen begint opnieuw.
  Is dat te krap, dan is ruimer maken een kleine aanpassing in `LiveHeartLimit`: Mark kiest.
- [ ] Zie wat de telefoon naar het horloge liet en wegliet: `adb logcat -s TandemWatch | grep "Sent to the watch"`. Per soort bericht staat er hoeveel er
  ging en hoeveel er is weggelaten. Als verstuurd en weggelaten allebei bij nul blijven terwijl het horloge klopt, is er iets mis.

## 0.3.26, 2026-11-18 (R-V19, hotspot per telefoon)

Wat veranderde: de Mac onthoudt de hotspot (naam en wachtwoord) per telefoon in plaats van een keer voor allemaal, en zegt het als een
telefoon een nieuwe of veranderde hotspot heeft: "Hotspot van <telefoon> is nieuw", als systeemmelding en als rij in het
menubalkpaneel, met een knop die het instelscherm opent met de naam al ingevuld. Een telefoon met Shizuku zegt zijn hotspotnaam zelf en
geeft het wachtwoord op een ja op de Mac, zodat er niets in te tikken is. Wat er al ingevuld stond gaat naar de eerste Android-telefoon.
Gezien in de Dev-app: de rij in het paneel en het instelscherm met de naam al ingevuld (met een nagebootste telefoon), en een
Android 16 emulator waarop de shell naam, beveiliging en wachtwoord van de hotspot leest. Niet gezien: echte telefoons.

- [ ] **Niets kwijt na de update.** Open op de Mac Instellingen, Hotspot: de naam en het wachtwoord van je telefoon staan er nog en onder de
  naam van die telefoon. "Zet de hotspot aan" op de pagina van de telefoon werkt zonder iets in te vullen.
- [ ] **Andere hotspotnaam, met Shizuku.** Verander op de telefoon de naam van de hotspot (Instellingen, Hotspot) terwijl de Mac verbonden
  is. Binnen ongeveer 20 seconden komt "Hotspot van <telefoon> is nieuw" met de nieuwe naam, als melding en als rij in het paneel.
  Een tweede keer dezelfde naam geeft geen tweede melding, ook niet na Tandem opnieuw starten.
- [ ] De knop in de rij ("Wachtwoord ophalen") of een klik op de melding opent de pagina van de telefoon met het instelscherm en de nieuwe
  naam al ingevuld. "Wachtwoord ophalen bij <telefoon>" vult het wachtwoord in zonder dat je iets tikt. Zet "Laat mijn Mac mijn hotspot
  gebruiken" op de telefoon uit en probeer het opnieuw: de Mac zegt dat de telefoon het wachtwoord niet doorgeeft.
- [ ] **Zonder Shizuku** (Shizuku uit): verander de hotspotnaam en laat de Mac zonder netwerk de hotspot vragen (of druk op "Zet de hotspot
  aan"). De telefoon zet hem aan, de Mac vindt hem niet en meldt een keer "Kon niet verbinden met de hotspot van <telefoon>" met de knop
  naar het instelscherm. Tik de naam en het wachtwoord in: daarna lukt verbinden weer.
- [ ] **Alleen het wachtwoord veranderd**: dezelfde melding, het instelscherm heeft de naam al, je tikt het nieuwe wachtwoord.
- [ ] **Een nieuwe telefoon**: koppel een tweede Android-telefoon en zet daar de hotspotschakelaar aan. Met Shizuku komt de melding zodra
  hij verbonden is, zonder Shizuku bij de eerste keer dat de Mac zijn hotspot probeert. De hotspot van de oude telefoon blijft werken.
- [ ] "Later" in de rij laat hem los en hij komt niet terug voor dezelfde naam. Een Mac waarop de hotspot nooit is ingesteld krijgt
  geen melding voor elke telefoon.

## 0.3.27, 2026-11-18 (PR 52, R-V18)

Wat veranderde: het horloge haalt stappen en hartslag van de dag nu bij Health Services (de eigen meting van het systeem) in plaats van
zelf elke 5 minuten de hartslagsensor aan te zetten en zelf de stappen te tellen. Zonder Health Services, zonder toestemming of als het systeem
zwijgt meet het horloge nog zelf, zoals voor deze versie. Live hartslag, slaapdetectie en de nachtopname zijn niet aangeraakt. Gebouwd en getest op een
Wear OS 6 emulator met nepgegevens, niet op een echt horloge: dat is jouw dag. Zie R-V18 in `docs/ROADMAP.md`.

- [ ] **Een dag om de pols, opnieuw.** Zelfde proef als bij 0.3.25: 's ochtends op volle lading, 's avonds het percentage noteren. Ligt het hoger dan
  met 0.3.25? De winst is een verwachting, geen meting.
- [ ] **De stappen kloppen.** Vergelijk het getal op de tegel van Tandem (telefoon en horloge) met het stappental in de eigen Google- of Pixel-
  fitness-app van het horloge: gelijk, of een paar procent verschil. Een dag die twee keer zo hoog is, is een fout (zie `adb logcat -s TandemWatchSteps`:
  de regel "From the system" zegt hoeveel totalen en stappen binnenkwamen).
- [ ] **De hartslagkromme van de dag** (tegel op de telefoon, kaart op de Mac) ziet er uit als vorige week: vlak 's nachts, hoger overdag. Gaten van een
  kwartier zijn niet erg, grote gaten wel.
- [ ] Wie levert wat: `adb logcat -s TandemWatchPassive TandemWatchHeart TandemWatchSteps` op het horloge. De eerste regels na een start melden
  `Passive: [...]` (wat dit horloge kan, ook de slaapstanden) en `Wanted: steps ..., heart ... Allowed: ...`. Staat er `steps false` of `heart false`, dan
  zegt de regel eronder welke toestemming mist. Bewaar de `Passive:` en de `Wanted:` regel, ook als alles goed gaat: dat is het bewijs voor de slaapstand.
- [ ] Noteer bij die twee regels welke Wear OS- en Android-versie het horloge heeft (Instellingen > Systeem > Over). Op Android 15 en ouder (Wear OS 5) loopt
  de aanvraag langs `BODY_SENSORS`, een pad dat alleen op papier is nagekeken; staat daar `heart false` of steps ook terug op de eigen teller, meld het.
- [ ] Telt het horloge `Measuring` (eigen meting) per uur veel minder dan `No measurement of our own` (overgeslagen)? Zo ja, doet Health Services
  het werk. Telt hij alleen `Measuring`, dan levert Health Services niets en valt alles terug: meld welke `Wanted:` regel erbij staat.
- [ ] Toestemming intrekken: haal bij Instellingen > Apps > Tandem de toestemming voor lichaamssensoren (of hartslag) weg. De hartslag blijft dan
  doorlopen via de eigen meting of stopt netjes, de app crasht niet. Zet hem terug en kijk of Health Services het weer overneemt (binnen een halfuur,
  dan registreert hij opnieuw).
- [ ] Op Android 16 en nieuwer (Wear OS 6): de knop voor hartslag op de achtergrond op de gezondheidspagina van het horloge geeft de toestemming
  die Health Services nodig heeft als het scherm uit is.
- [ ] Een nacht: de slaaptegel en de hartslag in de nacht zijn als een nacht eerder. De slaapdetectie en de opname zijn niet aangepast, dus een
  afwijking zou uit de hartslag komen, niet uit de slaap.
- [ ] Een tijd lang geen horloge om en dan weer om: de stappen van die dag lopen verder (geen herstart naar nul, geen dubbele telling).
- [ ] Een dagwissel om middernacht met het horloge om: de stappen beginnen bij 0 op de nieuwe dag, en de dag ervoor staat compleet in de geschiedenis.
- [ ] Meldingen, bellen, muziek en live hartslag werken als bij 0.3.25 (zelfde lijst als daar, snel doorlopen).

## 0.3.28, 2026-11-18 (R-V15 deel 2, accu onderweg)

Wat veranderde: de telefoon laat op mobiele data zijn werkende verbinding met de Mac met rust. Een scherm dat aan gaat of een VPN die
opnieuw verbindt telt niet meer als nieuw netwerk: alleen een andere set adressen die even blijft staat telt, of de computer die uit
slaap komt. Een verbinding krijgt bij zo'n melding geen Ping meer met een kapstok van 3 seconden (daar sneuvelden werkende
verbindingen op een slapende radio), en elke nieuwe verbinding stuurt de albumhoezen niet opnieuw die de Mac al heeft. De Mac belt
de telefoon niet elke paar seconden meer als de aanwijzer aan de rand staat. Een apparaat dat dagen weg is wordt op mobiele data niet
meer gebeld, ook als de VPN zegt dat het netwerk niet betaald is (`costly` leest nu het onderliggende transport). Gemeten op een
emulator met het verkeerslog (tellingen, geen mA, geen echte mobiele radio, geen Tailscale), en met kern- en eenheidstests.

- [ ] **Drie uur op mobiele data met het scherm vaak aan en uit.** Haal daarna het verkeerslog van de Oppo op (`adb pull
  /sdcard/Android/data/nl.markmaaktmedia.tandem/files/traffic`) en vergelijk met 0.3.25: `network_changed` van 108 in 3 uur naar
  hooguit een paar (alleen echte wisselingen), "closed here" van ongeveer 20 per uur naar bijna nul, Pings per 5 minuten met het
  scherm uit van 150 tot 270 pakketten naar een stuk minder. Daalt het stroomverbruik met het scherm uit onder de 254 mA?
- [ ] **De aanwijzer aan de rand van het scherm.** Zet de Mac naast de telefoon (aanwijzer delen), laat de aanwijzer een minuut aan de
  rand staan en kijk in het verkeerslog van de Mac: geen 63 tot 77 Pings per 5 minuten meer. En de aanwijzer gaat nog steeds over naar de
  telefoon als je hem naar de rand duwt, ook als de telefoon net uit zijn slaap komt.
- [ ] **De Mac uit de slaap.** Klap de Mac dicht, een paar minuten later weer open: de telefoon verbindt binnen een halve minuut weer
  (het log toont dan `woke_from_sleep`). Hetzelfde met de deksel van een Windows-laptop.
- [ ] **De telefoon oppakken.** Scherm uit, een paar minuten wachten, scherm aan: de Mac ziet de telefoon weer online en een bestand of
  het klembord komt meteen over. Er hoort geen nieuwe verbinding bij te komen als de oude nog werkt (log: geen `disconnected`).
- [ ] **Echt van netwerk wisselen** (wifi uit, mobiele data aan, en terug): de verbinding met de Mac wordt opnieuw gelegd en de wachttijd
  begint opnieuw, `network_changed` met `changed: true` staat een keer per wissel in het log. Komt er na een wissel niets en blijft
  de verbinding hangen tot de heartbeat hem een paar minuten later laat vallen, meld het: dan ziet de kern de adressen niet veranderen.
- [ ] **Muziek met hoes.** Speel iets met een albumhoes op de telefoon terwijl de Mac meekijkt, laat de verbinding een keer wisselen
  (vliegtuigstand een halve minuut): de hoes staat er na het terugkomen nog, en het verkeerslog toont geen tweede `MediaArt` van 12 KB.
  Sluit Tandem op de Mac en open hem weer: nu komt de hoes wel opnieuw.
- [ ] **Een computer die dagen uit staat** (de Linux-laptop) wordt op mobiele data niet meer elke 5 tot 10 minuten gebeld. Zet de telefoon
  op wifi: hij probeert hem weer zoals vroeger.

## 0.3.29, 2026-11-21 (klembord dat terugkwam, voorkeuren in een groep)

- [ ] **Screenshot naar de Mac.** Maak een screenshot op de telefoon en deel hem via Tandem naar de Mac met "foto op het klembord" aan.
  De foto staat op het klembord van de Mac, en de telefoon krijgt hem niet opnieuw als klembord binnen (geen tweede melding, geen
  nieuwe foto in de klembordmap). Een eigen copy op de Mac daarna gaat wel gewoon naar de telefoon.
- [ ] **Tekst via Quick Share of "invoegen vanaf telefoon".** Stuur tekst met Quick Share naar de Mac of voeg een foto van de telefoon
  in: niets daarvan komt terug naar de telefoon.
- [ ] **Telefoon met Shizuku-klembord.** Tekst die van de Mac over de aanwijzer of via Quick Share op de telefoon op het klembord komt,
  gaat niet terug naar de Mac.
- [ ] **Voorkeuren van een apparaat (telefoon, Instellingen voor dit apparaat).** Klembord, Meldingen, Bestanden automatisch en, als
  ze er zijn, Berichten, Slaap en trainingen en Nudges staan in een groep, met afgeronde buitenkant en kleine hoeken ertussen. Bekijk
  een Mac (alles), een Linux-pc, een andere telefoon (geen Nudges) en een tablet zonder simkaart (geen Berichten): de vormen kloppen steeds.
- Windows en Linux hadden dit pad al goed (de klembordcode onthoudt wat ze zelf zetten); daar valt niets nieuws te testen.

## 0.3.31, 2026-11-21 (kant kiezen en Instellingen onderaan, Linux en Windows)

Wat veranderde: op de pagina van een apparaat in de Linux- en Windows-app staat een rij "Where <naam> stands" met een pill Links of
Rechts van deze computer. Kiezen doet hetzelfde als het doosje van het apparaat links of rechts in de schermindeling zetten
(`arrange_side` roept `arrange::put` aan). Alleen in een browser met `mock.js` bekeken en met `check-linux.sh`, niet op echte toestellen.

- [ ] **Kant kiezen.** Open de pagina van een telefoon en van een computer: de rij staat bovenaan Instellingen. Kies Links: in Instellingen
  onder Muis springt het doosje van het apparaat naar links, en omgekeerd. Sleep het doosje daar naar rechts: de pill volgt.
- [ ] **Zonder keuze** staat Rechts gevuld, en een tik op Rechts zet het apparaat er echt neer (het doosje verschijnt rechts).
- [ ] **Zoek met geluid** (alleen bij een Mac of een telefoon met de nieuwste app): de knop staat naast de pill, en de uitkomst zet de pill om.
- [ ] **Teksten.** Op Windows staat "deze pc" in de ondertitel, op Linux "deze computer", in Engels en Nederlands.

En Instellingen staat in de zijbalk direct boven Apparaat toevoegen, onder de meldingsbanners (alleen in een browser met `mock.js` bekeken):

- [ ] **Brede zijbalk.** Onderaan staan Instellingen en Apparaat toevoegen in dezelfde stijl; Gedeeld, Berichten, Klembord en Meldingen staan
  erboven. Instellingen opent nog en is gemarkeerd als hij open is.
- [ ] **Smalle zijbalk** (venster smal maken): alleen icoon, met de tooltip "Instellingen" en "Apparaat toevoegen". Een update- of scherm-banner schuift Instellingen niet weg.

## 0.3.32, 2026-11-21 (accu-logboek op het horloge, tweede ronde R-V18)

Wat veranderde: het horloge schrijft per klokuur een korte regel over wat Tandem deed (wekmomenten, sensor-, verbindings-, scan- en
schermtijd, Bluetooth-brokken en berichten per soort) en wat de accu verloor, met een pagina Accu-logboek onder Instellingen op het
horloge. Een telefoon die over een halfuur weg is wordt een vijfde van de tijd gezocht, en de verbinding van een horloge in rust vraagt
om een zuinigere Bluetooth-stand. Op een Wear OS 6 emulator bekeken (logregel, bestand, pagina, scherm aan en uit); Bluetooth en de
accu zijn daar niet echt, dus de winst zelf is niet gemeten.

- [ ] **Een dag om de pols.** Doe het horloge 's ochtends om, gebruik het gewoon, en open 's avonds Instellingen, Accu-logboek. Per uur staat
  er een regel met het verlies in mAh (of procent) en de wekmomenten, de verbindings-, scan- en schermtijd. Zoek het uur waar het
  verlies uitspringt en kijk wat daar hoog staat. Vergelijk het percentage 's avonds met 0.3.31.
- [ ] **Meldingen, bellen, muziek en de aanwijzer voelen niet trager.** Met het scherm uit: stuur een melding en neem een gesprek aan. Het
  scherm aan, bediening van de muziek en de aanwijzer (de Mac of telefoon bedienen vanaf het horloge) reageren zoals voorheen. Een
  melding hooguit een tel later bij een horloge dat lang stil lag mag, meer niet.
- [ ] **Slaap, stappen, hartslag.** De nacht wordt nog herkend, een dutje ook, stappen en hartslag lopen door (de tegels op de telefoon
  kloppen met het horloge).
- [ ] **Telefoon lang weg.** Laat de telefoon een uur uit of ver weg. In het logboek staat de scantijd per uur dan rond een vijfde.
  Dat de telefoon na meer dan een halfuur weg pas na hooguit ongeveer 2,5 minuten gevonden wordt (30 s kijken per 2 minuten rust) is
  zo bedoeld en geen fout; het horloge kijkt meteen als je het scherm aanzet, en zonder een halfuur weg zit het er binnen ongeveer 3 minuten weer aan.
- [ ] **Zuinige verbinding.** `adb logcat -s TandemWatch` (draadloos) toont "Link priority: LOW_POWER" na een halve minuut rust en
  "Link priority: BALANCED" bij een stoot berichten of scherm aan. Valt de verbinding drie keer in een uur weg terwijl hij op zuinig
  stond, dan komt er 12 uur lang geen "LOW_POWER" meer in het logboek en telt de pagina geen zuinige minuten meer.
- [ ] **Het bestand.** `adb pull /sdcard/Android/data/nl.markmaaktmedia.tandem.watch/files/power` geeft een bestand per dag met een
  regel per uur (en `adb logcat -s TandemWatchPower` dezelfde regel). Bestanden ouder dan 14 dagen verdwijnen.
- [ ] **Teksten.** De pagina staat in het Nederlands (Accu-logboek, wekmomenten, scherm, link, scan) en in het Engels (Battery log, wake
  moments), en past op het ronde scherm.

## 0.3.33, 2026-11-21 (updatekaart op Mac en Linux/Windows)

Wat veranderde: de updatemelding is op de Mac (zijbalk en menubalkpaneel) en op Linux/Windows één tonale kaart: icoon, "Nieuwe versie X",
"Je hebt Y", hooguit twee regels uit de release notes, en Later en Bijwerken naast elkaar over de hele breedte. Na Bijwerken krimpt Later
weg en wordt Bijwerken een voortgangsbalk met het percentage (de regel eronder wordt "x van y MB"); als de download binnen is en
gecontroleerd, staat de balk vol op "Installeren…", wordt het icoon een groen vinkje en start de app zonder tweede klik opnieuw. Mislukt
het, dan staat er kort waarom en één brede knop "Opnieuw". Bekeken met `TANDEM_DEBUG_UPDATE` (Mac, nepversie zonder echte download) en
in de browser-mock (Linux/Windows). Het echte downloaden, controleren en vervangen is niet opnieuw uitgevoerd: die code is niet veranderd.
Android volgt apart met een gedeelde bibliotheek.

- [ ] **Mac, echte update.** Bij de eerstvolgende release: de kaart verschijnt in de zijbalk, Bijwerken toont de balk met MB en procent, daarna
  "Installeren…" met vinkje, en Tandem start vanzelf opnieuw op de nieuwe versie. Later verbergt alleen die versie.
- [ ] **Mac, menubalkpaneel.** Dezelfde kaart onderaan het paneel, past op de breedte zonder afgekapte tekst.
- [ ] **Mac, mislukt.** Zonder internet midden in de download: kaart "De update is mislukt" met een brede knop Opnieuw die weer begint.
- [ ] **Windows en Linux.** Zelfde kaart onderaan de zijbalk; bij een ingeklapte zijbalk blijft alleen het icoon. Op Windows installeert
  de installer na de download, op Linux vervangt de AppImage zichzelf. Bij een .deb of .rpm meldt hij alleen de nieuwe versie.
- [ ] **Nederlands.** Alle teksten van de kaart staan in het Nederlands als de taal Nederlands is.

## 0.3.32, 2026-11-21 (gezondheidstegels, tweede deel)

- [ ] **Telefoon, pagina van het horloge.** Tik op de tegel met de hartslag (BPM): je komt meteen op Hart, niet op het Gezondheidsoverzicht.
  Idem Slaap voor de nacht en Activiteit voor de stappen. Terug brengt je naar de pagina van het horloge. De kop "Gezondheid" bovenaan
  de kaart opent nog wel het overzicht. De tegels op de kaart van het startscherm gingen al naar hun eigen pagina.
- [ ] **Windows en Linux, pagina van het horloge.** Dezelfde drie tegels openen hun eigen deel (Slaap, Activiteit, Hart); de pijl terug
  gaat naar het horloge, en de kop Gezondheid opent nog het overzicht.
- [ ] **Hart: nu.** Met het horloge bij de telefoon: open Gezondheid, Hart. Bovenaan staat je hartslag nu met "Now" (NL "Nu") en een
  kloppend hartje; zonder live waarde (horloge weg) staat er het laatste getal zoals vroeger. Op Windows/Linux hetzelfde als de
  telefoon bij de computer is, op de Hart-pagina van het horloge.
- [ ] **Hart op het overzicht.** De kaart Hart op de Gezondheidspagina heeft 0, 6, 12, 18 onder de lijn en een dunne lijn op je
  gebruikelijke rusthartslag, zoals Slaap de dagen onder de balkjes heeft. Idem op Windows/Linux.

## 0.3.32, 2026-11-21 (speler op een eigen kaart, Android)

- [ ] **Android, pagina van een computer die muziek speelt.** De speler (hoes, titel, balk, knoppen) staat op een eigen kaart direct
  onder de kaart met naam, versie, pillen en accuring, met dezelfde ruimte als tussen de andere kaarten. Stop de muziek op een
  telefoon of tablet die alleen een speler toont als er iets speelt: de spelerkaart schuift weg en laat geen lege ruimte achter; start
  hem weer: hij komt terug met de animatie. Een bereikbare Mac of Windows-pc houdt zijn speler ook als er niets speelt.
- Linux en Windows hadden al twee kaarten (kop en speler naast elkaar); daar is niets veranderd.

## Mac-ontwerp, 2026-11-21 (PR 31, changelog 0.3.32)

Het nieuwe uiterlijk van de Mac-app. Mark heeft het in de Dev-app met testapparaten bekeken; met echte apparaten nog niet.

- [ ] **Kop van een apparaat.** Naam, systeem en versie van Tandem, rechtsboven de pil "Verbonden" (even ver van boven als van rechts),
  daaronder tegels voor accu, netwerk (naam van het wifi) en bij een telefoon het geluid, bij een computer de vrije ruimte. De speler
  staat ernaast als eigen kaart, met de keuze tussen spelers linksboven. Niets staat er leeg bij.
- [ ] **Bovenaan.** Boven de kaarten is evenveel ruimte als ernaast. Wisselen tussen apparaten in de zijbalk schuift elke pagina van
  onderen in beeld, ook de bovenste.
- [ ] **Iconen.** Een telefoon heeft een camerapil, de Xperia een gewoon scherm, een pc een scherm op een voet.
- [ ] **Waar het staat.** De keuze [telefoon Links] laptop [Rechts telefoon]: de vrije kant licht op onder de muis, de laptop is geen knop.
  Na een keuze steekt de muis aan die kant over en komt een tweede scherm daar.
- [ ] **Settings for this device.** De eigen kaart onder de instellingen opent een laag over het hele venster; erachter dimt alles en
  reageert niets op de muis, een klik ernaast, Esc of het kruisje sluit hem. Naam wijzigen (op een apparaat met 0.3.24 of nieuwer)
  komt op al je apparaten. Waar het staat: zoeken op geluid, vanzelf controleren, het geluid. Bij een computer "Mag deze Mac gebruiken".
  De schakelaars voor wat het apparaat op deze Mac mag (bestanden, bedienen, vergrendelen, herstarten, wifiwachtwoord) doen wat ze zeggen.
- [ ] **Horlogepagina.** "Verbonden" is dezelfde rustige pil, niet meer helemaal groen.

## 0.3.33, 2026-11-21 (updatekaart op Android, gedeelde module)

Wat veranderde: Android gebruikt de gedeelde module `android/markupdate` (ongewijzigd uit Keeper) voor het controleren, downloaden en installeren van updates en voor de kaart. Het horloge blijft zijn eigen updater houden.

### Te testen door Mark

- [ ] **Android, echte update**: bij een nieuwere release verschijnt de kaart bovenaan met wat er nieuw is. Update toont de voortgang en daarna "Installing", de app sluit kort en start bijgewerkt. Later verbergt de kaart voor die versie.
- [ ] **Android, onderbalk**: de balk onderaan en de inhoud schuiven mee als de kaart komt en gaat.
- [ ] **Android, Instellingen > Updates**: controleren, automatisch controleren aan en uit, en de samenvatting kloppen nog.

## 0.3.34, 2026-11-23 (hotspot zegt waarom het niet lukt)

Wat veranderd is:
- Lukt het automatisch aanzetten van de hotspot niet, dan zegt de Mac nu waarom, zoals de telefoon het weet: Shizuku draait niet (na een herstart van de telefoon), Shizuku niet geinstalleerd of geen toestemming, een fout van Shizuku met de tekst erbij, de hotspot kwam niet aan binnen 20 seconden, niemand tikte op de melding, of de telefoon gaf helemaal geen antwoord.
- Niemand tikte op de melding: de Mac hoort dat nu meteen van de telefoon in plaats van na ruim drie minuten wachten.
- Over Bluetooth (de Mac zonder netwerk): krijgt de telefoon het verzoek en gebeurt er daarna niets, dan zegt de Mac dat nu, in plaats van de algemene tekst over Shizuku.

Zelf testen:
- [ ] Oppo herstarten zonder Shizuku weer te starten, dan op de Mac de hotspot vragen: zegt hij dat Shizuku niet draait?
- [ ] Met Shizuku aan: gaat de hotspot nog gewoon vanzelf aan?
- [ ] Wifi op de Mac uit (dan gaat het via Bluetooth): zegt hij ook dan waarom het niet lukt?

## 0.3.35, 2026-11-23 (updatebalk Android)

- [ ] **Updatekaart op Android.** Tijdens een echte update (of Instellingen, Ontwikkelaar, Updatebanner): de balk is een volle blauwe vulling op een lichte
  baan, en het percentage staat in de vulling tegen de rand aan (wit), of net achter de rand op de baan (donker) zolang de vulling nog smal is. Het
  percentage wordt nooit doormidden gesneden. Bij Installeren is de hele balk vol blauw met het lichtje erover.

## 0.3.36, 2026-11-23 (bijwerken vanuit Instellingen, Android)

- [ ] **Instellingen, Updates en back-up.** Is er een nieuwere versie, dan staat er onder Nu controleren een brede balk "Bijwerken naar versie X". Een tik maakt er
  dezelfde voortgangsbalk van als op de updatekaart (percentage aan de rand van de vulling), daarna Installeren met de glans. De kaart bovenaan loopt mee.
  Is er geen nieuwe versie, dan staat er niets en zijn de twee groepen gewoon rijen onder elkaar. De losse regel "Versie X is beschikbaar" onder Nu controleren is weg.

## 0.3.36, 2026-11-23 (hotspot niet vragen met de klep dicht, Mac)

Wat veranderd is:
- Met de klep dicht wordt de Mac af en toe een paar seconden wakker (Power Nap). Tandem vroeg de telefoon dan om de hotspot, omdat "net nog gebruikt" de slaaptijd niet meetelt. Dat verzoek brak na een paar seconden af toen de Mac weer insliep, en de hotspot ging niet aan. Nu vraagt de Mac het alleen als het scherm aan is.
- Gaat de Mac slapen terwijl hij de hotspot vraagt, dan stopt hij daarmee. Bij openen staat er dan geen oude fout of "Asking your phone" meer.
- Klep weer open en nog geen netwerk: de Mac kijkt opnieuw (na de gewone wachttijd voor bekende netwerken) en vraagt dan de hotspot.

Zelf testen:
- [ ] Weg van bekende wifi, klep dicht, een kwartier wachten: de hotspot op de Oppo blijft uit.
- [ ] Daarna de klep open: binnen een minuut vraagt de Mac de hotspot en komt hij online.
- [ ] De knop om de hotspot aan te zetten werkt nog gewoon.

## 0.3.37, 2026-11-25 (hotspot bij de juiste telefoon, Mac)

Wat veranderd is:
- De Mac hield een status voor de hotspot bij, niet per telefoon. Met twee telefoons lichtte de hotspotknop bij allebei op en stond er bij allebei "Stop the hotspot". Stop op de verkeerde telefoon ging naar die telefoon, terwijl de Mac op de hotspot van de andere zat.
- Nu licht alleen de telefoon op waarvan de hotspot gebruikt wordt. De andere is grijs en zegt bij aanwijzen "Using the hotspot of ...". Op de pagina van die andere telefoon staat dat ook, met Turn on hotspot grijs.
- De kaart in het menu staat onder de juiste telefoon en Stop daarin gaat naar die telefoon.

- In de trein (2026-11-25) vroeg de Mac de oude Sony (via Bluetooth te zien, geen abonnement, geen hotspotwachtwoord op de Mac) en bleef op "Asking your phone" staan. De Oppo, waarvan de Mac het wachtwoord wel heeft, werd niet gevraagd.
- De Mac vraagt nu eerst de telefoons waarvan hij de hotspot kent, ook als hun Bluetooth-baken niet gehoord is. Lukt het bij een telefoon niet (niet gevonden, geweigerd, geen wachtwoord), dan vraagt hij vanzelf de volgende, elke telefoon een keer per ronde. Alleen bij een verzoek dat de Mac zelf doet; een druk op de knop vraagt alleen die telefoon.
- Terwijl de Mac een telefoon vraagt, kun je op de knop van de andere telefoon drukken: die neemt het over.
- Er staat nu "Asking <naam>" in plaats van "Asking your phone".
- Android: zonder simkaart voor mobiele data of met mobiele data uit weigert de telefoon meteen (reden 7 en 8, zie docs/HOTSPOT.md), zodat de Mac door kan naar de volgende. Geen bereik (tunnel) is geen reden om te weigeren. Ook de testknop op de telefoon zegt het.
- Nog niet verklaard: waarom de Oppo in de trein niet via Bluetooth te zien was.
- Linux en Windows hebben nog geen hotspot (zie windows/README.md), dus daar verandert niets.

Zelf testen:
- [ ] Twee telefoons in de buurt, de hotspot van een ervan in gebruik: alleen die knop is gekleurd, de andere is grijs met de uitleg.
- [ ] Stop in het menu en op de pagina van die telefoon zet de goede hotspot uit.
- [ ] Zonder wifi (trein), beide telefoons bij je: de Mac vraagt de Oppo en komt online. De Sony wordt niet eerst gevraagd.
- [ ] Op de Sony (zonder abonnement), Tandem, Hotspot, testknop: "Geweigerd, deze telefoon heeft geen simkaart voor mobiele data" (of mobiele data staat uit).

## 0.3.38, 2026-11-25 (updates vinden bij openen, Android)

Wat veranderd is:
- De schakelaar zegt "Every time you open the app", maar de app keek bij openen hooguit eens per zes uur. Een versie van tien minuten oud werd zo niet gezien. Nu hooguit eens per vijf minuten.
- Updates en back-up: zonder nieuwe versie plakten Check now en What's new tegen elkaar. Er zit nu altijd ruimte tussen de twee groepen.
- De Mac, Linux en Windows zeggen zelf "every few hours" en doen dat ook; daar verandert niets.

Zelf testen:
- [ ] Na de volgende release de app openen (niet Nu controleren): de updatekaart en de balk onder Check now verschijnen.
- [ ] Updates en back-up zonder nieuwe versie: tussen Check now en What's new zit dezelfde ruimte als tussen andere groepen.

## 0.3.39, 2026-11-25 (geen hotspotknop als de telefoon hem niet deelt)

Wat veranderd is:
- De telefoon zegt in zijn status of computers zijn hotspot mogen gebruiken: "Let my Mac use my hotspot" staat aan en er is een simkaart voor mobiele data.
- Zegt hij nee, dan heeft die telefoon op de Mac geen hotspotknop in het menu en geen hotspotkaart op zijn pagina, en de Mac vraagt hem niet meer. Alleen als de hotspot van die telefoon op dat moment gevraagd of gebruikt wordt, blijft de knop staan.
- Een hotspotfout verdween nooit. Nu na twee minuten, of zodra de Mac online is.
- Een telefoon met een oudere Tandem zegt niets; die houdt zijn knop.
- Linux en Windows hebben nog geen hotspot, dus daar verandert niets.

Zelf testen:
- [ ] Op de Sony "Let my Mac use my hotspot" uit (of geen simkaart): in het menu van de Mac is de hotspotknop bij de Sony weg, de pagina van de Sony heeft geen hotspotkaart.
- [ ] Weer aan: de knop komt binnen een paar seconden terug.
- [ ] Een hotspotfout in het menu is na twee minuten vanzelf weg.

## 0.3.40, 2026-11-25 (rustigere zijbalk, updatekaart)

Wat veranderd is:
- De apparaten houden hun volgorde van start tot start. De kern zet verbonden apparaten bovenaan, dus bij het openen sprong alles om terwijl ze een voor een verbonden. Nu blijft de volgorde staan; een nieuw apparaat komt onderaan, een verwijderd apparaat gaat eruit. Mac, Android, Linux en Windows. De eerste keer staat alles op naam (bij de start is nog niets verbonden), daarna blijft het zo.
- Mac, zijbalk: het paarse vlak glijdt niet meer van rij naar rij. Het staat meteen op de nieuwe rij en komt in 0,12 seconde op. Linux en Windows hadden geen glijdend vlak.
- Updatekaart, Mac, Linux en Windows: geen sterretjes meer, de tekst krijgt de ruimte. Op de Mac gaat de ruimte voor de kaart open in dezelfde beweging als de kaart binnenkomt (eerst was de ruimte er meteen en vloog de kaart er daarna in). De Android-kaart komt uit de gedeelde updatemodule van al je apps en is niet aangeraakt.
- Changelog: de regels van 0.3.37 tot en met 0.3.40 zijn kort gemaakt, zodat de kaart leest.

Zelf testen:
- [ ] Tandem op de Mac afsluiten en opnieuw openen: de apparaten in de zijbalk springen niet om.
- [ ] In de zijbalk een ander apparaat kiezen: het paarse vlak glijdt niet, het staat er meteen.
- [ ] Bij de volgende update: de kaart komt in een beweging binnen, zonder icoon, met korte regels.

## 0.3.41, 2026-11-25 (updatekaart zonder icoon, Android)

Wat veranderd is:
- De gedeelde updatemodule (bron: Keeper, `android/markupdate`, commit de6fa5a op branch `claude/updatekaart-zonder-icoon`) is opnieuw gesynct. De kaart heeft geen sterretje meer; titel, regel eronder en de korte regels beginnen aan de linkerrand. De foutkaart houdt zijn waarschuwingsicoon.
- Daarin zit ook de fix van de sessie voor de donkere stand (8bbf56d): tekst en rimpels van de kaart hebben hun eigen kleur, leesbaar in de donkere stand.

Zelf testen:
- [ ] Bij de volgende update op Android: de kaart bovenaan heeft geen icoon, de tekst loopt over de hele breedte.
- [ ] In de donkere stand is de tekst van de kaart goed leesbaar.

## 0.3.42, 2026-11-26 (hotspot na het openen van de klep)

Wat veranderd is:
- Na het openen van de klep (of een herstart) wachtte de Mac altijd 40 seconden voordat hij de telefoon om de hotspot vroeg, ook met de vertraging op 3 seconden. Op 26 november zette Mark de hotspot daardoor zelf aan, 2 seconden voordat Tandem het vroeg. Nu kijkt de Mac na de gekozen vertraging welke netwerken er zijn: staat er geen bewaard netwerk in de buurt (straat, trein), dan vraagt hij meteen. Is er wel een, of kan hij het niet zien (geen toegang tot locatie, wifi uit of nog bezig), dan wacht hij: 40 seconden na een herstart of als het netwerk pas bij het wakker worden wegviel, 15 seconden als de Mac al ruim voor het openen van de klep geen netwerk had (dan is hij ergens anders heen gedragen).
- Let op: Tandem heeft op Marks Mac geen toegang tot locatie (status "nog niet gevraagd", gezien in de log van locationd op 26 november). Zonder die toegang verbergt macOS de netwerknamen, ook voor `system_profiler`. Dan geldt dus de 15 seconden, niet de gekozen vertraging.
- Zonder netwerk op de Mac gaat de vraag altijd over Bluetooth, ook als de oude verbinding met de telefoon na het wakker worden nog even als verbonden telt.
- Leest Bluetooth vlak na het wakker worden nog even als uit, dan wacht de vraag tot 10 seconden tot het aan is, in plaats van meteen "zet Bluetooth aan" te melden.

Zelf testen:
- [ ] Eerst: op de Oppo staat Tandem › Instellingen › Hotspot › "Laat mijn computer mijn hotspot gebruiken" aan. Staat die uit, dan meldt de Oppo dat hij niet deelt, verdwijnt de hotspotknop op de Mac en vraagt de Mac de Sony (gezien op 26 november om 09:25 en 14:41).
- [ ] Klep dicht thuis, weg van bekende wifi klep open (na een paar minuten): binnen ongeveer 15 seconden vraagt de Mac de Oppo om de hotspot, en hij gaat erop. Met locatie toegestaan binnen de gekozen vertraging.
- [ ] Klep open thuis of op school: de Mac gaat op de eigen wifi en vraagt de telefoon niets.

## 0.3.43 (nog niet uitgebracht)

Wat veranderd is:
- De melding dat de hotspot nog ingevuld moet worden noemt nu de telefoon ("Vul eerst de hotspot van Maruks Sony Telefoon in bij Instellingen"). Onder de laatste apparaatrij leek hij bij de watch te horen.

Zelf testen:
- [ ] Vraagt de Mac een telefoon zonder bewaarde hotspot, dan staat de naam van die telefoon in de melding.
