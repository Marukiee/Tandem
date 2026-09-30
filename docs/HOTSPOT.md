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
verzoek.

**Zelf aanzetten door de telefoon (Shizuku, optioneel).** Een gewone app mag de hotspot
niet starten. Met Shizuku kan Tandem het wel, want Shizuku draait met de rechten van de
shell en de shell heeft `TETHER_PRIVILEGED`.

1. Installeer Shizuku (Play Store of github.com/RikkaApps/Shizuku).
2. Zet Ontwikkelaarsopties en Draadloos foutopsporen aan, open Shizuku en kies "Starten via
   Draadloos foutopsporen". Volg de stappen daar.
3. Open Tandem, Instellingen, Hotspot, tik op Methode en sta Tandem toe in het Shizuku-venster.
4. Druk op "Hotspot testen": de hotspot gaat 20 seconden aan.

Shizuku moet na elke herstart van de telefoon opnieuw gestart worden. Zonder Shizuku
(of als het niet draait) krijg je een melding "Je Mac wil je hotspot, tik om hem aan te
zetten" die het hotspotscherm van Android opent. Je Mac wacht dan tot drie minuten.

## Regels op de telefoon

- Alleen aan als de schakelaar aan staat en BLUETOOTH_ADVERTISE en BLUETOOTH_CONNECT zijn toegestaan.
- Geweigerd onder 15 procent accu (behalve aan de lader) en in roaming (tenzij "Ook in roaming" aan staat).
- Uit op verzoek en na 5 minuten zonder verbonden apparaten. Een hotspot die jij zelf aanzette blijft met rust.
- Zolang de Mac de hotspot gebruikt staat er een melding "Je Mac gebruikt je hotspot" met een Stopknop.

## Het BLE-protocol

Service `6f2d7a10-8b1c-4e6f-a3d5-1c9e5b7f2a40`. De telefoon is GATT-server en
adverteert, de Mac is centraal (CoreBluetooth).

| Characteristic | UUID (laatste cijfer) | Eigenschap | Inhoud |
|----------------|------------------------|------------|--------|
| challenge | `...11...` | read | 16 verse willekeurige bytes per read, eenmalig, 30 s geldig |
| request | `...12...` | write met response | het getekende verzoek |
| state | `...13...` | notify en read | 2 bytes: status en aantal clients (255 is onbekend) |

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

**Statuscodes** (byte 0 van state): 0 uit, 1 start, 2 aan, 3 handmatig (tik op de melding),
4 mislukt, 5 verzoek niet vertrouwd, 6 accu te laag, 7 roaming, 8 uitgezet op de telefoon.

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
- `cmd wifi start-softap` start alleen het toegangspunt zonder DHCP en NAT, dus geen
  internet. Tandem gebruikt `TetheringManager.startTethering`.
