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
