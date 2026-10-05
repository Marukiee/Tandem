# Quick Share in Tandem

Doel: bestanden delen met echte Quick Share-apparaten (Android-telefoons met Quick Share, Windows-pc's met Quick Share) en ze
ontvangen, zonder Google Play Services en op de Mac, Windows en Linux ook. Zonder account, in de modus "Iedereen". Aan en uit te
zetten met een schakelaar, standaard uit.

Wat dit niet is: AirDrop. Dat vraagt AWDL en is voor een app van derden niet te bouwen (zie ROADMAP). Voor iPhones is LocalSend de
eerlijke tweede stap.

## Wat er bekend is

Gebaseerd op de beschrijvingen van de open projecten NearDrop (macOS, ontvangen) en rquickshare (Linux en macOS, ontvangen en
versturen, GPL-3.0) en de Chromium-broncode van Nearby Share. Dit is een samenvatting om van te bouwen. Niets hiervan is in Tandem
bewezen tot er een echt apparaat mee heeft gewerkt. Code van die projecten wordt niet overgenomen, het protocol wordt zelf
geschreven (de licenties van rquickshare en Tandem zijn verschillend).

### Vinden
- Een ontvanger meldt zich op het lokale netwerk met mDNS, servicetype `_FC9F5ED42C8A._tcp` (afgeleid van SHA-256 van
  "NearbySharing"). De servicenaam is 10 bytes in URL-veilige base64: een byte 0x23, vier tekens als endpoint-id, drie bytes
  `FC 9F 5E` en twee nullen.
- In de TXT-sleutel `n` staat de endpoint-info in base64: een byte met versie, zichtbaarheid en apparaattype, 16 bytes
  identificatiegegevens (zout en versleutelde metasleutel) en de naam van het apparaat met een lengtebyte erbij.
- Android adverteert zijn mDNS niet altijd uit zichzelf. Een Bluetooth-advertentie met service-UUID `FE2C` en een bepaald
  voorvoegsel in de servicedata wekt dat. Voor ontvangen van een telefoon is mDNS genoeg zolang de telefoon naar ons zoekt. Voor
  versturen naar een telefoon is die Bluetooth-advertentie waarschijnlijk nodig.
- Er bestaat een QR-code-variant (`https://quickshare.google/qrcode#key=...`) die we niet nodig hebben.

### Verbinden en versleutelen
- TCP, elk bericht met een lengte van vier bytes (big-endian) ervoor, inhoud protobuf.
- Volgorde: ConnectionRequest (met de endpoint-info), UKEY2 ClientInit, ServerInit, ClientFinish, dan ConnectionResponse als
  laatste onversleutelde bericht.
- Uit UKEY2 komen twee D2D-sleutels (HKDF-SHA-256, zout SHA-256("D2D"), info "client" en "server") en daaruit vier sleutels voor
  versleutelen en ondertekenen (zout SHA-256("SecureMessage"), info "ENC:2" en "SIG:1").
- Alles daarna zit in secure messages: AES-256-CBC met PKCS7, HMAC-SHA-256, een IV van 16 bytes, een volgnummer per richting dat
  bij 1 begint. Een PIN voor de gebruiker volgt uit de authenticatiestring.

### Bestanden
- De zender stuurt een Introduction met de metadata van de bestanden en een payload-id per bestand. De ontvanger antwoordt met
  ACCEPT of REJECT (of NOT_ENOUGH_SPACE).
- Bestanden gaan als PAYLOAD_TRANSFER: een header (id, type FILE of BYTES, totale grootte) en brokken (offset, vlag LAST_CHUNK,
  data). Elke 10 seconden een KEEP_ALIVE van beide kanten.
- Er worden ook paired-key-berichten uitgewisseld waarvan de inhoud er in "Iedereen"-modus niet toe doet.

## Wat er niet in zit
- De modus "alleen contacten": die hangt aan Google-accounts en certificaten van Google. Alleen "Iedereen".
- Snelheidsupgrade naar Wi-Fi Direct of een hotspot. Over het lokale netwerk is het snel genoeg om mee te beginnen.

## Eerlijke onzekerheden
- **Mac naar telefoon:** een Mac kan met CoreBluetooth waarschijnlijk geen servicedata adverteren, alleen een naam en service-UUID's.
  Dan wekt hij de telefoon niet. Werkt het toch als de telefoon Quick Share open heeft staan, dan is dat genoeg. Dit moet met een
  echte telefoon worden uitgeprobeerd, het is de meest onzekere route.
- **Telefoon naar Mac:** dit is de bewezen route van NearDrop: de Mac meldt zich via mDNS en de telefoon vindt hem.
- **Android naar Android zonder Play Services:** we adverteren zelf met `BluetoothLeAdvertiser` en `NsdManager`. Dat zijn gewone
  Android-API's. Of de Quick Share van Google ons apparaat betrouwbaar toont en accepteert moet op een echte telefoon blijken.
- Google kan het protocol veranderen. Dat is onderhoud dat we niet kunnen voorkomen.

## Plan in stappen
1. **Kern (Rust, `crates/tandem-core/src/quickshare/`):** mDNS adverteren en zoeken, TCP met lengte-voorvoegsel, UKEY2, secure
   messages, ontvangen van bestanden, daarna versturen. Tests met twee exemplaren in één proces en vaste testvectoren voor de
   sleutelafleiding.
2. **Mac-app:** ontvangen, schakelaar in de Instellingen, scherm om te accepteren of te weigeren. Proberen met een echte telefoon.
3. **Android-app:** Bluetooth-advertentie en mDNS met de standaard API's, ontvangen en versturen, schakelaar en lijst van
   apparaten in de buurt. Op elk toestel zonder Play Services.
4. **Windows en Linux.**
5. **Mac naar telefoon** (Bluetooth), en eventueel de snelheidsupgrade.

## Wat Tandem hier niet doet
De namen "Quick Share" en "AirDrop" zijn merken van anderen. In de app staat "compatibel met Quick Share", geen eigen naam die erop
lijkt.
