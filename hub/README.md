# Tandem Hub

De Hub is een apparaat dat altijd aan staat en gewoon in je circle zit, bijvoorbeeld een
server op je tailnet of een Raspberry Pi. Je telefoon en je computers kunnen er bestanden
naartoe sturen, ook als je Mac dicht is. Ontvangen bestanden komen in `/data/inbox`.

Onder de motorkap is het `tandemd run` in een container. De Hub is geen aparte server:
hij spreekt hetzelfde versleutelde protocol als elk ander apparaat en vertrouwt alleen
apparaten die je zelf hebt gekoppeld.

## Draaien

Je hebt Docker met Compose nodig op een Linux-machine. De Hub gebruikt
`network_mode: host`, omdat apparaten elkaar op je netwerk vinden via mDNS en omdat hij
bereikbaar moet zijn op de Tailscale-interface. Dat werkt niet in Docker Desktop op een
Mac of Windows.

```bash
cd hub
docker compose up -d --build
```

De naam die andere apparaten zien is standaard "Tandem Hub". Wijzig je die, zet dan
`TANDEM_NAME=Thuisserver` in een bestand `hub/.env` en start opnieuw.

Laat UDP-poort 47820 toe in je firewall als je er een gebruikt, bijvoorbeeld
`sudo ufw allow 47820/udp`.

## Koppelen

```bash
docker compose exec tandem-hub tandemd --data-dir /data pair-show
```

Dat toont een QR-code en een link. Scan de code met Tandem op je telefoon of Mac. De code
werkt vijf minuten. Zodra de Hub gekoppeld is, kennen al je andere apparaten hem ook,
want de circle deelt zichzelf.

## Apparaten en bestanden

```bash
docker compose exec tandem-hub tandemd --data-dir /data devices   # wie is er online
docker compose logs -f                                            # wat er gebeurt
docker compose exec tandem-hub ls /data/inbox                     # ontvangen bestanden
docker compose cp tandem-hub:/data/inbox ./inbox                  # naar je eigen map
```

Wil je de bestanden rechtstreeks op de schijf, vervang dan in `docker-compose.yml` de
volume door `./data:/data` en draai de container als de eigenaar van die map
(`user: "1000:1000"`).

## Bijwerken en bewaren

```bash
git pull
docker compose up -d --build
```

Alles wat de Hub onthoudt staat in de volume `tandem-data`: zijn sleutel (zijn identiteit
in je circle), de circle zelf en de inbox. Raak je die kwijt, dan moet je de Hub opnieuw
koppelen.

## Later: pushmeldingen

In `docker-compose.yml` staat een uitgecommentarieerde `ntfy`-service. Tandem gebruikt
hem nog niet, maar zo ligt hij klaar voor pushmeldingen naar apparaten die op dat moment
niet verbonden zijn.
