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
