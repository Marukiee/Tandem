# Beveiliging

## Wat er beschermd wordt

Alles wat tussen je apparaten gaat: bestanden, klembord, meldingen, oproepen,
status en invoer. Niemand op hetzelfde wifi, niemand op de route, en geen relay
kan meelezen of meeschrijven.

## Hoe

- **Versleuteld en geauthenticeerd** met TLS 1.3 over QUIC. Beide kanten bewijzen
  dat ze de privésleutel hebben, dus er is geen anonieme kant.
- **Vertrouwen is een sleutel.** Er is geen certificaatautoriteit en geen server die
  je moet vertrouwen. Een apparaat is van de circle als een ander lid voor zijn
  sleutel heeft ingestaan.
- **Koppelen is gebonden aan een geheim** dat alleen via de QR-code reist, en aan het
  versleutelde kanaal zelf. Wie meeluistert op het netwerk kan niet koppelen.
- **Eenmalig en kortlevend**: een koppelcode werkt één keer en verloopt na vijf
  minuten. Na drie foute pogingen is hij weg.
- **Privésleutels** staan in de beveiligde opslag van het toestel (Android Keystore,
  Sleutelhanger op macOS) en verlaten het toestel nooit.
- **Geen tracking op het netwerk.** De mDNS-aankondiging wisselt elk uur en is alleen
  voor leden te herkennen.

## Wat niet beschermd wordt

- Een apparaat dat gestolen is en nog niet uit de circle is gehaald. Verwijder het
  zo snel mogelijk vanaf een ander apparaat. Zodra het verwijderen bij de andere
  apparaten is aangekomen, weigeren ze het.
- Een gecompromitteerd lid kan in het venster voor het verwijderen nog anderen
  toevoegen. Zulke leden worden in de app gemarkeerd als "toegevoegd door een
  verwijderd apparaat" zodat je ze kunt nakijken.
- Metadata zoals dat twee apparaten praten, en hoeveel, is voor een waarnemer op
  de route zichtbaar. De inhoud niet.

## Updates

- Android controleert de update met de handtekening van Android zelf: een APK met een
  andere sleutel wordt niet geïnstalleerd over de bestaande app.
- De Mac-app controleert een Ed25519-handtekening over het archief en vergelijkt de
  signing-identiteit van de nieuwe app met die van de draaiende.

## Een kwetsbaarheid melden

Open een privé beveiligingsmelding via de tab Security van deze repo, of mail
mark@markmaaktmedia.nl. Graag niet direct een publiek issue.
