# Het Tandem-protocol, versie 1

Dit document beschrijft wat er over de lijn gaat, zodat iemand anders een
compatibele client kan schrijven (bijvoorbeeld voor Linux). De Rust-core in
`crates/tandem-core` is de referentie-implementatie. Waar dit document en de code
verschillen, wint de code en is dit document fout.

## Overzicht

```
apparaat A  <=== QUIC, TLS 1.3, gepinde Ed25519-sleutels ===>  apparaat B
   |  stream 1: controle (berichten, lang open)
   |  stream 2..n: een bestand per stream
   |  datagrammen: pointerbewegingen
```

Alles loopt over UDP met QUIC (RFC 9000). Dat geeft meerdere onafhankelijke streams
(een groot bestand houdt een melding niet op), snelle herverbinding en versleuteling
in één handshake.

## Identiteit

Elk apparaat heeft één Ed25519-sleutelpaar. Het apparaat-id is de eerste 16 bytes van
de SHA-256 van de publieke sleutel, als base32 zonder padding in kleine letters
(26 tekens). Het id kan dus niet gekozen of geclaimd worden zonder de sleutel.

Het TLS-certificaat is zelfondertekend, met die sleutel. Vertrouwen hangt aan de
sleutel, niet aan het certificaat, dus het certificaat mag elke start opnieuw
gemaakt worden.

## Verbinden

- ALPN `tandem/1` voor normaal verkeer, `tandem-pair/1` voor koppelen.
- Alleen TLS 1.3. Beide kanten sturen een certificaat (wederzijdse authenticatie).
- **De beller pint** de sleutel die hij verwacht. Een verkeerde server breekt de
  handshake af voordat het clientcertificaat is verstuurd.
- **De aannemer** accepteert op TLS-niveau elke Ed25519-sleutel, en controleert
  meteen na de handshake of de sleutel in de circle zit. Zo niet: verbinding sluiten
  met code 1. Er wordt niets verwerkt voor die controle.
- Standaardpoort UDP 47820. Andere poorten worden meegestuurd in de adreslijst.
- Zijn beide kanten tegelijk aan het bellen, dan blijft de verbinding over die
  gemaakt is door het apparaat met het laagste id. De andere sluit met code 2.
- Wie een verbinding zo afwijst, stuurt de bestaande een `Ping` en sluit die met code
  4 als er binnen drie seconden geen `Pong` komt. Een app die net is bijgewerkt of
  afgesloten zegt niets, en zonder deze controle zou het apparaat dat terugkomt moeten
  wachten tot de rusttijd om is (45 seconden, op Android 70).

## Berichtformaat

Een frame is `u32` big-endian lengte, gevolgd door één CBOR-waarde (RFC 8949).
Maximale lengte: 8 MiB op de controlestream, 64 KiB voor kleine frames.

Berichten zijn een extern getagde enum: `{ "Ping": { "nonce": 5 } }`. Onbekende velden
worden genegeerd, onbekende berichtsoorten worden overgeslagen. Nieuwe velden krijgen
een standaardwaarde. Zo kan een nieuwere app met een oudere praten.

Bytes zijn CBOR-bytestrings, geen lijsten van getallen. Adressen zijn strings
(`"192.168.1.20:47820"`), nooit een compacte binaire vorm.

## Streams

De eerste byte van elke stream die een kant opent zegt wat volgt.

| Byte | Soort     | Richting                          |
|------|-----------|-----------------------------------|
| 1    | controle  | de beller opent hem, precies een  |
| 2    | bestand   | de ontvanger opent hem            |

### Controlestream

1. De beller stuurt `1` en een `Hello`. De aannemer antwoordt met zijn `Hello`.
2. Verschillen de `circle_digest`-waarden, dan stuurt elke kant een `CircleSync` met
   al zijn verklaringen.
3. Daarna vrij verkeer in beide richtingen: `Status`, `Clipboard`, `ShareOffer`,
   `Notification`, `Call`, enzovoort. Zie `crates/tandem-core/src/proto.rs` voor de
   volledige lijst met velden.

### Bestanden

De ontvanger haalt bestanden op, de afzender bedient alleen. Dat geeft de ontvanger de
regie over gelijktijdigheid en hervatten.

1. Afzender stuurt `ShareOffer { id, items }` op de controlestream.
2. De ontvanger opent per bestand een stream: byte `2`, dan `FileRequest { offer,
   index, offset }`.
3. De afzender antwoordt `FileResponse { ok, size }`, stuurt de ruwe bytes vanaf
   `offset`, en sluit af met `FileTrailer { blake3 }`: de BLAKE3 van het **hele**
   bestand. Bij hervatten leest de afzender het begin lokaal mee zonder het te sturen.
4. De ontvanger vergelijkt met zijn eigen hash en antwoordt `FileAck { ok }`.

Onvolledige bestanden staan als `.part` en worden pas hernoemd na een goede hash.

### Invoegen vanaf de telefoon

Een computer vraagt de telefoon om een foto, een gescand document of een plaatje uit de
galerij. De telefoon opent zijn camera, de computer toont dat hij wacht.

1. De computer kiest een willekeurig `id` en stuurt `CaptureRequest { id, kind }` op de
   controlestream. `kind` is `photo`, `document` of `picture`. Een soort die de telefoon
   niet kent beantwoordt hij meteen met `CaptureCancel { id, why: unavailable }`.
2. Is het gelukt, dan stuurt de telefoon het resultaat als gewone bestandsdeling
   (`ShareOffer`) met `origin` gelijk aan `{ "capture": id }`. De computer haalt het op
   zoals elk bestand en weet aan het id bij welke vraag het hoort. Een bod met een
   `capture`-id dat niet bij een lopende vraag van deze computer hoort is gewoon een
   bestand dat binnenkomt en krijgt dezelfde behandeling als elke andere deling.
3. `CaptureCancel { id, why }` beëindigt een vraag zonder bestand, in beide richtingen.
   De computer stuurt het als de persoon annuleert of de tijd om is, zodat het scherm op
   de telefoon sluit. De telefoon stuurt het als de persoon het scherm sloot (`cancelled`),
   de camera niet gebruikt mag worden (`refused`) of niet beschikbaar is (`unavailable`).
   Ontbreekt `why`, dan betekent het `cancelled`.

Een oudere versie leest `origin` `capture` niet als bekende waarde en slaat het bod over,
maar die kan ook geen vraag gesteld hebben.

## De circle

De ledenlijst is een verzameling ondertekende verklaringen (`Add`, `Remove`,
`Rename`), elk met de sleutel van de ondertekenaar, een Lamport-teller `seq`, en een
Ed25519-handtekening over `"tandem-circle-statement-v1"` gevolgd door de CBOR van de
verklaring zonder handtekening.

- De eerste `Add` waarbij ondertekenaar en onderwerp gelijk zijn is de wortel.
  Andere zelf-verklaringen tellen niet.
- Een `Add` telt als de ondertekenaar op dat moment lid is en niet verwijderd met een
  teller lager dan of gelijk aan die van de verklaring.
- Een `Remove` is blijvend. Wie terug wil, maakt een nieuwe identiteit.
- Lidmaatschap is een pure functie van de verzameling, dus twee apparaten met
  dezelfde verzameling zijn het altijd eens, in welke volgorde die ook binnenkwam.
- Twee apparaten met verschillende `circle_digest` sturen elkaar alles en voegen samen.

## Koppelen

1. Apparaat A toont een QR met `tandem://pair/1?...`: zijn sleutel, adressen en een
   eenmalig geheim van 32 bytes.
2. B scant, belt A met ALPN `tandem-pair/1` en pint de sleutel uit de QR.
3. B stuurt `PairRequest` met een HMAC-SHA256 over de TLS-exporter (kanaalbinding),
   B's sleutel en het geheim. Een man-in-het-midden kent het geheim niet.
4. A controleert de HMAC, het geheim (eenmalig, verloopt na vijf minuten) en het aantal
   pogingen, ondertekent een `Add` voor B en antwoordt met alle verklaringen.
5. B controleert de handtekeningen en dat hij er zelf in staat, en neemt de circle over.
6. A geeft de nieuwe verklaring door aan de andere leden zodra ze verbinden.

## Ontdekking

- **mDNS**: `_tandem._udp.local.`, met een TXT-veld `h` dat elk uur wisselt en alleen
  door leden van de circle te herkennen is. Een buitenstaander ziet niets waarmee hij
  een apparaat kan volgen.
- **Onthouden adressen** uit eerdere verbindingen en de `Hello.candidates` van elke
  peer, inclusief het Tailscale-adres.
- Alle bekende adressen worden gelijktijdig geprobeerd, met een klein voorsprongetje
  voor het LAN. De eerste geslaagde handshake wint.

## Versies

`Hello.proto` is het hoogste protocolnummer dat de afzender begrijpt. Deze versie is 1.
