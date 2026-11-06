# Tandem Watch: horlogelink v1

Dunne, versleutelde link tussen één telefoon en één horloge over BLE, zonder Google-diensten op de telefoon. Het horloge is geen
lid van Tandem's circle en staat los van de bestaande BLE-hotspotroute. Koppelen en instellingen staan in Tandem op de telefoon
(een kaart tussen de apparaten op de startpagina; koppelen via Koppel een apparaat).

| Module | Wat |
| --- | --- |
| `android/watch-protocol` | berichten, versleuteling, koppelen, framing. Pure Kotlin, met tests |
| `android/watch-sleep` | ruwe sensoropslag, slaapanalyse, model, training, stappen. Pure Kotlin, met tests |
| `android/watch-sleep/training` | de scripts die het slaapmodel maken, met uitleg. Python, draait niet in de app |
| `android/watch` | de Wear OS-app (`nl.markmaaktmedia.tandem.watch`) |
| `android/app/.../watch` | de telefoonkant in Tandem: `WatchBridge`, meldingen, gesprekken, muziek, muis, stille slaap |
| `crates/tandem-core/src/health.rs` | slaap en trainingen van de telefoon naar de computers |

## Rollen en transport
- Telefoon: GATT-server, adverteert alleen als er geen horloge verbonden is.
- Horloge: GATT-client, scant op de service-UUID en verbindt opnieuw na elke onderbreking. Herkenning gaat met de sleutels,
  niet met het MAC-adres, want dat wisselt zonder bonding.
- Service `7b1e4a50-9c3d-4f6e-8a21-5d0c6e7f1a00`, horloge naar telefoon (write met antwoord) `...1a01`, telefoon naar
  horloge (notify) `...1a02`. MTU-verzoek 247, stukgrootte `(mtu - 3).coerceIn(20, 180)`.
- Chunk: 1 byte kop (bit 7 = laatste stuk, bit 0-6 = volgnummer). Een gat verbreekt de verbinding; herstel gaat via de
  synchronisatie na het herverbinden. Maximum 64 KiB per bericht.

## Stabiliteit
Dit zijn de regels die uit de review en de tests kwamen. Laat ze staan bij het overzetten.
- Eén notify tegelijk, wachten op `onNotificationSent`. Het horloge schrijft met antwoord en wacht op `onCharacteristicWrite`.
- Na elke fout `gatt.close()` en opnieuw met oplopende wachttijd (1,5, 2, 4, 8, 16, 30 s). De teller gaat pas terug als een
  link langer dan 30 s heeft gestaan. Scans blijven daarmee onder de grens van 5 starts per 30 s.
- **De verbinding overleeft een herstart van de telefoon-app.** Het horloge merkt dan niets en meldt zich niet opnieuw aan,
  terwijl de telefoon daarop wacht. Het horloge reageert daarom op `onServiceChanged`. De telefoon vergeet bovendien een
  verbinding die zich niet binnen 15 s aanmeldt, omdat `cancelConnection` zo'n geërfde verbinding niet altijd sluit.
- Ping elke 5 minuten vanaf het horloge, 20 s wachten op antwoord, anders opnieuw verbinden.
- Bluetooth uit en aan: de GATT-server wordt opnieuw geopend, de oude overleeft dat niet.
- Berichten worden pas versleuteld als ze de wachtrij verlaten, en `Call` haalt alles in.

## Koppelen
Het horloge is een accessoire van deze ene telefoon, geen circle-lid. Het krijgt dus geen toegang tot bestanden, invoer of
andere apparaten.

1. Koppelen openen op telefoon en horloge. Het venster op de telefoon staat 2 minuten open.
2. Horloge stuurt `SHA-256(publieke sleutel + nonce)`, telefoon stuurt zijn X25519-sleutel, horloge onthult sleutel en nonce.
   Door die commitment kan iemand ertussen niet zoeken naar sleutels die dezelfde code geven.
3. Beide schermen tonen dezelfde zescijferige code, afgeleid van beide sleutels en de nonce. Pas na bevestiging op beide
   toestellen wordt de linksleutel opgeslagen, verpakt met de Android Keystore. `allowBackup` staat uit.

## Sessie
Elke verbinding: `Hello` met willekeurige bytes in beide richtingen, daarna HKDF over linksleutel en beide nonces (met versie
en rol in de info), met een eigen AES-256-GCM-sleutel per richting en een strikt oplopende teller. Mislukte authenticatie stopt
de poging; het horloge vraagt dan om opnieuw te koppelen.

## Berichten
Staat wordt gesynchroniseerd, niet gestreamd: na elke verbinding stuurt het horloge `SyncRequest` en antwoordt de telefoon met
`Call`, `NotificationSnapshot` (alleen sleutels), de losse meldingen en `MediaPlayers`. Alleen de nieuwste melding die nog niet
getoond is en jonger is dan 10 minuten trilt. Zo gaat er bij een onderbreking niets verloren en trilt er niets dubbel.

| Type | Bericht | Richting | Velden |
| --- | --- | --- | --- |
| 0x01 | SyncRequest | H naar T | |
| 0x02/03 | Ping, Pong | beide | id |
| 0x04 | Unpair | beide | |
| 0x10 | NotificationSnapshot | T naar H | keys |
| 0x11 | Notification | T naar H | key, app_id, app_name, title, text, sub_text?, ts, ongoing, silent, buttons (id, title, is_reply), otp?, progress? |
| 0x12 | NotificationRemoved | T naar H | key |
| 0x13 | NotificationAction | H naar T | key, button, reply?, dismiss |
| 0x14 | AppIcon | T naar H | app_id, image (ronde WebP van 96 bij 96, hooguit 6 KB; vóór de eerste melding van die app, per verbinding één keer) |
| 0x20 | Call | T naar H | null of: id, state, incoming, number?, name?, ts, muted |
| 0x21 | CallAction | H naar T | id, kind |
| 0x30 | MediaPlayers | T naar H | id, app, title, artist, album, playing, position_ms, duration_ms, art_key |
| 0x31 | MediaCommand | H naar T | player id, kind |
| 0x32 | MediaArt | T naar H | key, image (WebP van 152 bij 152, hooguit 6 KB; alleen als hij verandert), player id |
| 0x40 | FindDevice | beide | ring |
| 0x41 | DeviceBatteries | T naar H | id, name, level, charging per apparaat |
| 0x42 | WatchStatus | H naar T | level, charging |
| 0x43 | SleepMode | H naar T | on. Staat, geen opdracht: wordt na elke verbinding herhaald |
| 0x44 | Ringing | beide | on |
| 0x50 | HealthRecord | H naar T | id, workout, start, eind, slaapminuten, wakker, wakker-momenten, efficiëntie, licht?, diep?, REM?, stukken (fase, seconden), hartslag gem?, laag?, hoog?, stappen |
| 0x51 | HealthAck | T naar H | id. Pas als het record veilig op de telefoon staat |
| 0x70-75 | PointerTargets, PointerSession, PointerMove, PointerScroll, PointerClick, PointerKey | beide | muis en gyro, alleen achter de schakelaar in Tandem |

Codering: compact binair formaat, geen CBOR, want de Rust-kern doet hier niet mee en BLE is smal. Velden worden alleen achteraan
toegevoegd en onbekende types genegeerd.

## Regels
- Gespreksgeluid blijft op de telefoon (zie `AGENTS.md`). Het horloge neemt op, weigert, zet stil en hangt op, en zegt eerlijk
  "Praat op je telefoon".
- Eenmalige codes komen niet op het horloge: de telefoon maskeert ze. Vanaf Android 15 verbergt het systeem zulke meldingen
  bovendien zelf voor apps als deze ("Sensitive notification content hidden").
- Meldingen zijn gewone Wear OS-meldingen. Ontkoppelen haalt ze van het horloge, en ook de bewaarde app-iconen.
- De player werkt als die van Drip: wat speelt staat vooraan, daarna wat als laatste speelde (`PlayerRules`), dus een pauze laat hem staan en de volgende uit de lijst van het systeem neemt het niet over. Hooguit drie players, elk met een eigen hoes; wisselen met de kleine hoesjes of door naar links te vegen, want naar rechts sluit een scherm op een horloge. Een keuze houdt op als die player weg is of als iets anders begint te spelen. Sluit een app zijn mediasessie echt af, dan is hij ook hier weg.
- Het horloge heeft een eigen mediasessie met wat de telefoon speelt (`PhoneMediaSession`). Daardoor behandelt Wear OS het als muziek op het horloge, met zijn eigen player en bediening; elke druk gaat naar de telefoon.
- Een sessie alleen is niet genoeg: de media-app van Wear OS kijkt pas als een `MediaStyle`-melding naar de sessie wijst. Die app zet dan het icoon op de wijzerplaat en een rij in de meldingen; tikken daarop opent de `sessionActivity`, dus onze player. De melding zelf staat op de laagste belangrijkheid, anders staat de muziek twee keer in de lijst. Het icoon is er alleen zolang er echt iets speelt: bij pauze haalt het systeem het weg, wat het bij elke player doet. De volumeknop gaat alleen tijdens het spelen naar de telefoon; bij pauze regelt hij het volume van het horloge.
- Een sessie zonder titel of in de staat `NONE` telt niet als player. De NOS-app houdt er zo een open, en die stond na een herstart vooraan.
- Het app-icoon staat er als large icon: het small icon tekent Wear OS niet, en bovenaan blijft de naam "Tandem" met een klein badge. De naam vervangen (`android.substName`) negeert het horloge.
- De dienst die de verbinding openhoudt heeft geen kaart in de lijst. Hij geeft als tijd 1 op en dan weigert het systeem de melding als te oud (`Ignored enqueue for old` in logcat), terwijl de dienst gewoon foreground blijft. Stopt een latere Wear OS daarmee, dan komt alleen de kaart terug.

## Slaap en training
- Alles staat op een eigen pagina Gezondheid (`HealthPage` op de telefoon, een eigen onderdeel in de instellingen op de computers). Zonder horloge in de kring staat daar alleen de schakelaar "Ook zonder horloge tonen", per apparaat bewaard. De nachten zelf blijven bewaard als een horloge wordt ontkoppeld.
- Opnemen: versnellingsmeter op 25 Hz, hartslag, stappen, off-body en huidtemperatuur (als die toestemming er is), in batches uit de
  sensorhub zodat de processor kan slapen. Een bestand per sessie in de externe app-map (`Android/data/.../files/raw`), 30 dagen
  bewaard. Alleen daar kan een release-build een nacht over USB afgeven.
- De ruwe data blijft staan, zodat een betere analyse oude nachten opnieuw kan doorrekenen:
  `./gradlew :watch-sleep:analyze --args="nacht.tws"`.
- **Vanzelf starten en stoppen** (`SleepAutoDetector`, `AutoSleep`, uit te zetten op het horloge): tussen 21:00 en 11:00, om de pols,
  niet aan de lader, 25 minuten stil. Stopt bij 8 bewogen minuten binnen 12, of als het horloge af of aan de lader gaat. Een
  automatische opname die geen nacht blijkt, wordt weggegooid. Wie met de hand stopt, krijgt 3 uur geen nieuwe automatische start.
- **Fasen**: een klein BiGRU-netwerk (twee lagen, 48 per richting) op 19 kenmerken per 30 s uit hartslag en beweging, drie netten
  gemiddeld, daarna Viterbi met geleerde overgangen. Met de hand geschreven in Kotlin (`SleepModel`), dus zonder nieuwe afhankelijkheid;
  het model is een resource. `SleepModelTest` bewijst dat kenmerken en uitkomst per getal gelijk zijn aan die van Python.
- **Regels eromheen** (`SleepAnalyzer`): niet gedragen of 20 minuten geen hartslag en geen beweging telt niet als slaap. Fasen worden
  niet getoond als minder dan de helft van de nacht hartslag heeft: uit beweging alleen zou het gokken zijn.

### Wat het horloge niet geeft
De Pixel Watch 2 geeft een gewone app alleen bpm rond 0,5 Hz. PPG, ECG en losse hartslagen vragen
`com.google.pixelwatch.permission.READ_PRIVATE_SENSORS`, een recht dat alleen systeemapps krijgen. Daarom haalt dit model niet de
cijfers van onderzoek met losse hartslagen (Fonseca 2023: kappa 0,64).

### Hoe goed het is
Eerlijk gemeten: nachten van mensen die het model niet zag, 284 nachten van 78 mensen. Kappa, vier klassen (wakker, licht, diep, REM).
Deze cijfers komen uit de evaluatie in `training/`. "Alleen hartslag" is dezelfde training met dezelfde verdeling van mensen, met de
bewegingskenmerken op nul.

| Meting | Alleen hartslag | Met beweging |
| --- | --- | --- |
| Alle nachten, hele mensen apart gehouden | 0,546 | 0,554 |
| Mediaan per nacht | 0,563 | 0,564 |
| Wakker tegen slaap (twee klassen) | 0,578 | 0,623 |
| Alleen de nachten thuis | 0,550 | 0,557 |
| Alleen de nachten in een slaaplab | 0,515 | 0,528 |
| Getraind op nachten thuis, getest op nachten in een slaaplab | 0,514 | 0,543 |
| Idem, wakker tegen slaap | 0,443 | 0,513 |

| Fase, met beweging | Gevonden | Klopt | Mediane fout per nacht |
| --- | --- | --- | --- |
| Wakker | 57% | 77% | 11 min |
| Licht | 74% | 69% | 33 min |
| Diep | 69% | 71% | 23 min |
| REM | 70% | 68% | 26 min |

Beweging helpt vooral bij de vraag of iemand wakker is, en wanneer een model nachten uit een andere omgeving krijgt. Op de fasen zelf doet
het weinig: over alle nachten stijgt de kappa 0,008, en de mediane fout is voor licht, diep en REM 2 tot 3 minuten groter (wakker 2
kleiner). Getraind thuis en getest in het lab is die fout 2 tot 6 minuten groter, ook voor wakker. Elke variant is een keer gemeten, dus
zulke kleine verschillen kunnen ook toeval zijn. De meting gebruikt een net per deel; het model dat meegaat, middelt er drie over alle
nachten en is niet apart te meten.

De tabellen hierboven lezen de nacht met de geleerde overgangen zoals ze zijn. Dat streek bijna elk kort moment wakker weg: op 64% van de
nachten bleef geen minuut wakker over, terwijl dat er echt 2% zijn. Daarom tellen de overgangen in de app voor 0,4 mee en krijgt wakker er
0,6 bij (`SleepModel`), zonder de netwerken opnieuw te trainen. De getallen zijn gekozen uit een raster in `training/postproc.py`, midden in
een vlak gebied en dus niet afgesteld op een nacht. Op een echte nacht met een bevestigd moment wakker bleef dat moment nu staan, en kwam
er verder niets bij.

| Meting | Was | Nu | 95%-interval van het verschil, per persoon |
| --- | --- | --- | --- |
| Kappa, alle nachten | 0,554 | 0,557 | -0,001 tot +0,007 |
| Wakker tegen slaap | 0,623 | 0,616 | -0,021 tot +0,008 |
| Mediane fout in minuten wakker tijdens de nacht | 10 | 8 | -4,5 tot -1,5 |
| Nachten zonder een minuut wakker (echt 2%) | 64% | 32% | |
| Wakker gevonden, en klopt | 57%, 77% | 65%, 66% | |
| Getraind thuis, getest in het lab: kappa | 0,543 | 0,542 | -0,011 tot +0,008 |
| Idem, wakker tegen slaap | 0,513 | 0,532 | -0,008 tot +0,045 |
| Idem, nachten zonder een minuut wakker (echt 0%) | 68% | 45% | |

De prijs: in het lab wordt de mediane fout voor licht groter (44 naar 56 minuten) en die voor REM kleiner (30 naar 24). Korte momenten wakker
blijven gemist worden: het model ziet 2,1 wisselingen per uur waar het er echt 5,5 zijn, en schat de tijd wakker nog 5 minuten te laag.

Ter vergelijking: menselijke scorers onderling rond 0,76, commerciële wearables in onafhankelijke vergelijkingen rond 0,2 tot 0,5
(secundaire bronnen). Nog niet gemeten: hoe dit model het op de hartslag en beweging van de Pixel doet. Beide datasets komen van een
Apple Watch, en bij de Pixel is geen slaaplab.

### Naar de computers
`HealthRecord` gaat met een blijvende wachtrij (`HealthOutbox`) naar de telefoon, één tegelijk, en blijft staan tot `HealthAck`. De
telefoon geeft hem aan de core, en die geeft hem door aan de apparaten die de schakelaar aan hebben op hun pagina in Tandem op de
telefoon. **Standaard staat die uit**: hoe iemand sliep is niet voor elke Hub of gedeelde computer in een circle. Uitzetten laat dat
apparaat ze vergeten, ook als het op dat moment weg was. Gaat de telefoon of de computer zelf uit de circle, dan wist de computer wat
van die telefoon kwam, zodra hij dat hoort. Opslag `health.cbor`, hooguit 1500 records (de oudste gaan), berichten van hooguit 50 records.

### Naamsvermelding
Het model is getraind op twee open datasets onder ODC-BY 1.0:
- Walch O. et al., "Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography" (`sleep-accel` 1.0.0,
  PhysioNet), Sleep 2019, doi 10.1093/sleep/zsz180.
- "A Multi-Night Instantaneous Heart Rate and Accelerometry Dataset with EEG Sleep Stage Labels" (`bidsleep-dataset` 1.0.1, PhysioNet).

## Teststand
Op twee eigen emulators (AOSP, API 35, virtuele Bluetooth), met de horloge-app op een telefoonimage:

| Onderdeel | Resultaat |
| --- | --- |
| Koppelen, "Klopt niet" stopt het | werkt |
| Meldingen, lange tekst, 15 tegelijk, weghalen, antwoorden, alles wissen | werkt |
| Oproep: opnemen, ophangen, weigeren, beller haakt af, stil, wakker bij uit scherm | werkt |
| Bluetooth telefoon of horloge uit en aan | terug 1 tot 2 s na aanzetten |
| Vijf keer snel achter elkaar | terug in 3 tot 17 s (wachttijd loopt op) |
| Horloge-app crasht, horloge herstart | terug in 3 en 7 s |
| Telefoon-app gekild (3x), telefoon-app bijgewerkt | terug in 4, 6, 17 s en 2 s |
| Wat veranderde tijdens een breuk | hersteld na herverbinden |
| Ontkoppelen vanaf beide kanten, telefoon vergeet horloge | werkt, horloge vraagt om opnieuw koppelen |

Ook op de emulators, met Tandem op de telefoon: zoeken, batterijen, stille slaap. En de hele keten met een verzonnen nacht: analyse op
het horloge, over virtuele Bluetooth naar de telefoon, met de schakelaar aan naar een aparte `Tandem Dev` op de Mac, daar op de pagina, en
met de schakelaar uit weer weg. Standaard kwam er niets aan. De regels staan ook in `crates/tandem-core/tests/health.rs`, met echte
engines over QUIC: alleen naar gekozen apparaten, inhalen na weg zijn, vergeten na uitzetten. De pagina op Windows en Linux is alleen
bekeken in de browser met `mock.js`.

Op de echte Pixel Watch 2 (Android 17), nog zonder telefoon: installeren, achtergronddienst, hartslag om de pols (0,52 Hz, ook met
het scherm uit), vanzelf starten en stoppen, en scrollen: bij 16 veegbewegingen 0,28% haperende beelden in de `fast`-build, tegen 22%
in debug. Daarom draait een horloge de `fast`-build (R8, ingekorte resources, debug-sleutel), geen debug.

**Nog niet getest:** Bluetooth tussen het echte horloge en de Xperia, de muis in een echte computer, de kroon, pinch om de pols
(alleen op kunstmatige data afgesteld), een hele nacht, Doze en de batterij over een nacht. Een emulator bewijst niet dat de echte Bluetooth-stack zich hetzelfde gedraagt.

## Testdraaiboek echt horloge
Bouwen vanuit `android/`, met `ANDROID_HOME` en `JAVA_HOME` zoals in `AGENTS.md`:

```bash
./gradlew --console=plain -q :watch:assembleFast :app:assembleDebug
```

Installeren (horloge via de laadkabel, ADB-foutopsporing aan, serienummers uit `adb devices -l`):

```bash
adb -s HORLOGE install -r -g watch/build/outputs/apk/fast/watch-fast.apk
```

```bash
adb -s TELEFOON install -r app/build/outputs/apk/debug/app-debug.apk
```

Op de telefoon: Tandem openen, toestemmingen en meldingstoegang geven.

**Reset het horloge niet.** Na een reset wil het de eerste setup, en die lukt waarschijnlijk alleen met een Android-telefoon met
Google. Haal het om dezelfde reden niet weg uit een eventuele Pixel Watch-app.

Daarna, in deze volgorde:
1. Koppelen: op de telefoon Koppel een apparaat, Koppel een horloge; op het horloge "Pair with phone"; code vergelijken, op beide bevestigen.
2. Melding: kijken of hij trilt en er goed uitziet, antwoorden met het toetsenbord van het horloge.
3. Oproep: gebeld worden met het scherm uit. Scherm moet aangaan en trillen. Opnemen, ophangen, weigeren.
4. Media: muziek afspelen op de telefoon, bedienen vanaf het horloge.
5. Afstand: telefoon buiten bereik en weer terug. Bluetooth uit en aan op beide. Horloge herstarten. Telefoon herstarten.
6. Eén nacht om de pols: batterij begin en eind noteren, en of de link bleef staan.
7. Slaap op een computer: op de telefoon de pagina van die computer openen en "Slaap en trainingen" aanzetten.

```bash
adb -s HORLOGE shell dumpsys deviceidle whitelist +nl.markmaaktmedia.tandem.watch
```

Gebruik die regel alleen als de link in Doze wegvalt, en meld dat dan: het zegt iets over de batterij.

Ruwe nacht ophalen voor analyse:

```bash
adb -s HORLOGE pull /sdcard/Android/data/nl.markmaaktmedia.tandem.watch/files/raw/BESTAND.tws nacht.tws
```

Logs: `adb logcat -s TandemWatch:I TandemWatchTracking:I TandemHealth:I` op het horloge.

De knop "Pinch test" zit alleen in de debug-build van het horloge.

## Open punten
1. Accessoire in plaats van circle-lid: akkoord van Mark nodig.
2. Wear Compose is een nieuwe Gradle-afhankelijkheid: akkoord van Mark nodig.
3. De vaste service-UUID in de advertentie is herkenbaar in de buurt. Voor v1 aanvaardbaar.

## Later
Trainingen met GPS en de Garmin Forerunner 255 (zie `docs/ROADMAP.md`), weigeren met een sms, en Google-onderdelen op het horloge
uitschakelen via ADB zodra de link stabiel is.
