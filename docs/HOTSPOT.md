# Automatische hotspot

Als je Mac geen internet heeft, vraagt hij de gekoppelde Android-telefoon om zijn
hotspot aan te zetten, maakt er verbinding mee en meldt dat op beide kanten. Zonder
netwerk kan dat niet over QUIC, dus het verzoek gaat over Bluetooth Low Energy (BLE).
Heeft de Mac nog wel een QUIC-verbinding met de telefoon, dan gaat hetzelfde verzoek
daarover (`HotspotMsg::Request`, antwoord `HotspotMsg::State`).

## Installatie

**Telefoon.** Zet in Tandem, Instellingen, Hotspot "Laat mijn Mac mijn hotspot
gebruiken" aan en geef Bluetooth toe onder Rechten. Alleen dan adverteert de telefoon.

**Mac.** Vul in Instellingen, Hotspot de naam en het wachtwoord van de hotspot van je
telefoon in (eenmalig, het wachtwoord staat in een privebestand op de Mac) en zet "Gebruik
de hotspot van mijn telefoon" aan. Bluetooth-toestemming vraagt de Mac bij het eerste
verzoek. Naam en wachtwoord gelden voor die ene telefoon (zie "Een nieuwe telefoon" hieronder).

**Zelf aanzetten door de telefoon (Shizuku, optioneel).** Een gewone app mag de hotspot
niet starten. Met Shizuku kan Tandem het wel, want Shizuku draait met de rechten van de
shell en de shell heeft `TETHER_PRIVILEGED`.

1. Installeer Shizuku (Play Store of github.com/RikkaApps/Shizuku).
2. Zet Ontwikkelaarsopties en Draadloos foutopsporen aan, open Shizuku en kies "Starten via
   Draadloos foutopsporen". Volg de stappen daar.
3. Open Tandem, Instellingen, Shizuku (of Hotspot, Methode) en sta Tandem toe in het Shizuku-venster. Die pagina laat ook zien
   of Shizuku als root of als de shell draait en waar Tandem het verder voor gebruikt.
4. Druk op "Hotspot testen": de hotspot gaat 20 seconden aan.

Shizuku moet na elke herstart van de telefoon opnieuw gestart worden. Zonder Shizuku
(of als het niet draait) krijg je een melding "Je Mac wil je hotspot, tik om hem aan te
zetten" die het hotspotscherm van Android opent. Je Mac wacht dan tot drie minuten.

## Een nieuwe telefoon, of een hotspot met een andere naam of een ander wachtwoord

De Mac onthoudt naam en wachtwoord per telefoon (`HotspotProfiles`: de namen in de voorkeuren, de wachtwoorden in het privebestand
`hotspot-phones.secret`). Wat er voor deze versie was ingevuld (een naam en een wachtwoord voor alle telefoons) gaat naar de eerste
Android-telefoon die de Mac kent, zodra hij er is: niemand hoeft iets opnieuw in te vullen. Vraagt de Mac om een hotspot, dan kiest
hij een telefoon die in bereik is en zijn hotspot ingevuld heeft, en pas daarna de eerste.

Een hotspot die de Mac niet kent, merkt de Mac op twee manieren:

1. **De telefoon zegt zijn naam** (`HotspotMsg::Name`, en `ssid` in een `State`), aan elke verbonden Mac, een keer en opnieuw als de
   naam verandert, zolang "Laat mijn Mac mijn hotspot gebruiken" aan staat. Dat kan alleen met Shizuku: geen app mag de naam of het
   wachtwoord van de eigen hotspot lezen (`getSoftApConfiguration` is een systeem-API), de shell wel (hij heeft NETWORK_SETTINGS).
   Het gaat over QUIC, dus terwijl de Mac nog een netwerk heeft en je nog tijd hebt. Een eigen bericht en geen `State`, omdat een
   `State` een antwoord is en een Mac die op zijn antwoord wacht dat anders voor het antwoord zou aanzien.
2. **Verbinden mislukt**: de naam is niet te vinden of het wachtwoord wordt geweigerd (`HotspotWiFiError.pointsAtCredentials`).
   Dat werkt voor elke telefoon, ook zonder Shizuku en ook over Bluetooth. Een time-out of "geen route" telt niet.

In beide gevallen komt er een melding "Hotspot van <telefoon> is nieuw" (systeemmelding en een rij in het menubalkpaneel) met een knop
die de pagina van die telefoon opent met het instelscherm en de naam al ingevuld. Wat de beslissing neemt staat in
`HotspotRules` (testbaar zonder app): een keer per naam, onthouden over een herstart; een nieuw ingevuld wachtwoord of een
verbinding die daarna wel lukt wist dat, zodat een volgende fout weer nieuws is. Een Mac die de hotspot nooit instelde krijgt de
melding niet voor elke telefoon die hij ontmoet. "Later" laat de rij los. Er is geen blijvende waarschuwing: wat niet kan, zegt
de status bij gebruik.

**Wachtwoord van de telefoon halen.** Zegt de telefoon zijn naam, dan kan hij ook het wachtwoord geven, zonder tikwerk. Het instelscherm
heeft dan "Wachtwoord ophalen bij <telefoon>": een ja op de Mac, dan gaat een `Ask { ssid }` over de bestaande Wi-Fi-deelroute
(`wifi.rs`) naar de telefoon, die het wachtwoord uit zijn eigen hotspot leest en als `Share` terugstuurt. De Mac slaat het op bij die
telefoon en verbindt er niet mee (`HotspotCoordinator.handleWifi` pakt dat antwoord voordat `WifiShare` het ziet). De telefoon
antwoordt alleen als "Laat mijn Mac mijn hotspot gebruiken" aan staat; een oudere telefoon antwoordt `Refuse` en je tikt het wachtwoord.

## Regels op de telefoon

- Alleen aan als de schakelaar aan staat en BLUETOOTH_ADVERTISE en BLUETOOTH_CONNECT zijn toegestaan.
- Geweigerd onder 15 procent accu (behalve aan de lader), in roaming (tenzij "Ook in roaming" aan staat) en als de daglimiet voor data is bereikt.
- Uit op verzoek en na 5 minuten zonder verbonden apparaten. Een hotspot die jij zelf aanzette blijft met rust.
- Zolang de Mac de hotspot gebruikt staat er een melding "Je Mac gebruikt je hotspot" met een Stopknop. Op de regel staat wat er vandaag is gebruikt, onder de melding (uitgeklapt) ook wat deze sessie gebruikte.

## Data per dag

De mobiele data die de hotspot gebruikt wordt geteld zoals hij wordt gebruikt, niet pas aan het eind van een sessie: elke 5 tot 15 seconden gaat het verschil in de mobiele bytes van het systeem (`TrafficStats`) naar het totaal en naar de dag van vandaag. Een sessie die wordt afgebroken (app herstart, hotspot met de hand uit) verliest dus hooguit de seconden sinds de laatste meting, en er begint niets opnieuw. Het totaal wordt alleen met de knop "Teller resetten" op nul gezet. De dagen (de laatste 60, `HotspotUsage.kt`) staan op de hotspotpagina.

Het is het mobiele verkeer van de hele telefoon zolang de hotspot aan staat, dus inclusief wat de telefoon zelf doet. Een telling per interface of per client kan een app zonder extra rechten niet lezen (`/sys/class/net` is geblokkeerd).

De limiet geldt per dag: is hij bereikt dan gaat een hotspot die een Mac vroeg uit, en weigert de telefoon nieuwe verzoeken tot de volgende dag (statuscode 9, in QUIC de fout `limit`).

## Het BLE-protocol

Service `6f2d7a10-8b1c-4e6f-a3d5-1c9e5b7f2a40`. De telefoon is GATT-server en
adverteert, de Mac is centraal (CoreBluetooth).

| Characteristic | UUID (laatste cijfer) | Eigenschap | Inhoud |
|----------------|------------------------|------------|--------|
| challenge | `...11...` | read | 16 verse willekeurige bytes per read, eenmalig, 30 s geldig |
| request | `...12...` | write met response | het getekende verzoek |
| state | `...13...` | notify en read | 3 bytes: status, aantal clients (255 is onbekend) en de reden van mislukken (0 is geen) |

**Advertentie.** De service-UUID staat in de advertentie. In de scanrespons staat als
service data (zelfde UUID) een label van 8 bytes: de eerste 8 bytes van
SHA-256("tandem-ble-hint-v1" || apparaat-id || uur) met uur als u64 big-endian van de
Unix-tijd gedeeld door 3600. Het wisselt elk uur en alleen wie het id van de telefoon kent
kan het herkennen, dus niemand kan de telefoon volgen. De Mac probeert het vorige, huidige
en volgende uur. Ziet hij geen label (scanrespons niet doorgegeven), dan neemt hij na 3
seconden de enige kandidaat.

**Verzoek** (100 bytes bij een id van 26 tekens):

```
[versie 1][actie 1=aan 2=uit][tijd u64 BE in ms][id-lengte][id ASCII][handtekening 64]
```

De handtekening is Ed25519 over `"tandem-hotspot-v1" || challenge || apparaat-id || actie ||
tijd (u64 BE)`. Beide apps bouwen die bytes met dezelfde Rust-functie
(`tandem_hotspot_auth_message`), dus ze kunnen niet uit elkaar lopen. De tijd zit in de
handtekening zodat hij niet te wisselen is, maar de versheid komt van de challenge: die is
eenmalig, en een fout antwoord verbrandt hem ook. Vijf mislukte pogingen in een minuut
sluiten de verbinding.

De telefoon controleert met `verify_member`: alleen een huidig lid van de circle met een
geldige handtekening telt. Een goed verzoek krijgt een ATT-succes, een slecht verzoek ATT-fout
`0x80` (bewust niet "onvoldoende authenticatie", want dat laat de Mac koppelen via Bluetooth).

**Actie 5** is hetzelfde als actie 4 (aan, op verzoek van de persoon), met eenmalig 1 GB bovenop de daglimiet voor vandaag. Over QUIC is dat `Request` met de reden `extra`.

**Statuscodes** (byte 0 van state): 0 uit, 1 start, 2 aan, 3 handmatig (tik op de melding),
4 mislukt, 5 verzoek niet vertrouwd, 6 accu te laag, 7 roaming, 8 uitgezet op de telefoon,
9 daglimiet voor data bereikt.

**Reden van mislukken** (byte 2 van state, bij status 4): 1 Shizuku niet geinstalleerd, 2 Shizuku draait niet,
3 Tandem heeft geen toestemming in Shizuku, 4 Shizuku gaf een fout, 5 Shizuku zette hem aan maar hij kwam niet
binnen 20 seconden, 6 niemand tikte op de melding, 7 geen simkaart voor mobiele data, 8 mobiele data staat uit. Over QUIC
staat dezelfde reden in `error` als `failed:<sleutel>`
(`shizuku-missing`, `shizuku-stopped`, `shizuku-permission`, `shizuku-error`, `not-up`, `no-tap`, `no-sim`, `data-off`), bij
`shizuku-error` gevolgd door `:` en wat Shizuku zei. Een oudere Mac leest alleen byte 0 en ziet elke `failed...`
als mislukt, dus dit breekt niets. De Mac zelf voegt twee redenen toe: geen antwoord (over QUIC 45 seconden niets, over Bluetooth 20 seconden geen enkele status na het verzoek) en verzoek wel ontvangen maar niets gedaan (over Bluetooth alleen de "uit" van daarvoor). Een tweede weigering vlak na de eerste wordt ook weer gestuurd.

**Volgorde op de Mac.** Verbinden, services en characteristics ontdekken, notificaties op
state aanzetten, challenge lezen, ondertekenen met `sign_message`, request schrijven, dan
state volgen tot "aan" (of een weigering). Lange writes (prepared write) worden door de
telefoon ook aangenomen.

## Daarna op de Mac

Wifi joinen met CoreWLAN (opgeslagen SSID en wachtwoord), anders met
`networksetup -setairportnetwork`. Dan de standaardgateway (`route -n get default`) als
adres van de telefoon aan de core geven (`add_address(id, "gateway:47820")`), zodat QUIC
meteen omhoog komt. De Mac laat de hotspot los als er een kabel komt of hij internet vindt
op een ander netwerk.

## Wat er niet is

- Geen root en geen Shizuku nodig voor de handmatige route.
- `startLocalOnlyHotspot` is geen optie: die deelt geen internet.
- Zonder Shizuku kan een telefoon zijn eigen hotspotnaam en wachtwoord niet lezen en dus niet zeggen of geven. De Mac merkt dan
  pas bij het verbinden dat het mislukt, en je tikt de naam en het wachtwoord in.
- `cmd wifi start-softap` start alleen het toegangspunt zonder DHCP en NAT, dus geen
  internet. Tandem gebruikt `TetheringManager.startTethering`.
