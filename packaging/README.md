# Tandem op Linux

`tandemd` is de achtergronddienst van Tandem voor Linux. Hij houdt je computer online
in je circle, ontvangt bestanden en klembordtekst en laat je zelf iets sturen vanaf de
opdrachtregel. Er is geen venster, alles gaat via de terminal.

## Installeren

1. Download het juiste bestand van de
   [laatste release](https://github.com/Marukiee/Tandem/releases/latest):
   `tandemd-linux-x86_64.tar.gz` voor een gewone pc of `tandemd-linux-aarch64.tar.gz`
   voor bijvoorbeeld een Raspberry Pi. In de terminal:

   ```bash
   curl -LO https://github.com/Marukiee/Tandem/releases/latest/download/tandemd-linux-x86_64.tar.gz
   curl -LO https://github.com/Marukiee/Tandem/releases/latest/download/tandemd-linux-x86_64.tar.gz.sha256
   sha256sum -c tandemd-linux-x86_64.tar.gz.sha256
   ```

2. Pak uit en zet `tandemd` in `~/.local/bin` (staat bij de meeste distributies al in
   je `PATH`):

   ```bash
   tar xzf tandemd-linux-x86_64.tar.gz
   install -Dm755 tandemd-linux-x86_64/tandemd ~/.local/bin/tandemd
   tandemd --version
   ```

3. Zet de dienst aan, zodat Tandem draait zodra je inlogt:

   ```bash
   install -Dm644 tandemd-linux-x86_64/tandemd.service ~/.config/systemd/user/tandemd.service
   systemctl --user daemon-reload
   systemctl --user enable --now tandemd
   ```

   Wil je dat hij ook draait zonder dat je ingelogd bent (een server, een Pi), doe dan
   eenmalig `loginctl enable-linger $USER`.

## Koppelen

```bash
tandemd pair-show
```

Dat toont een QR-code en een link. Scan de code met Tandem op je telefoon of Mac (of
plak de link). De code werkt vijf minuten. Andersom kan ook: heeft een ander apparaat
een koppellink getoond, dan koppel je met `tandemd pair "<link>"`.

## Gebruiken

```bash
tandemd devices                       # apparaten in je circle en of ze online zijn
tandemd send telefoon foto.jpg        # bestanden sturen (naam of begin van het id)
tandemd clip telefoon "tekst"         # tekst naar het klembord van een apparaat
tandemd whoami                        # het id en de naam van deze computer
```

Ontvangen bestanden komen in `~/Downloads/Tandem`. De sleutel en je circle staan in
`~/.local/share/tandem`. Bewaar die map als je je apparaat-id wilt behouden.

De logs lees je met `journalctl --user -u tandemd -f`.

## Firewall

Tandem luistert op UDP-poort 47820. Draai je een firewall, laat die poort dan toe op je
lokale netwerk (en op je tailnet als je Tailscale gebruikt), bijvoorbeeld
`sudo ufw allow 47820/udp`.

## Bijwerken en verwijderen

Op Linux werkt Tandem zichzelf niet bij. Download de nieuwe `tar.gz`, vervang
`~/.local/bin/tandemd` en start de dienst opnieuw:
`systemctl --user restart tandemd`.

Verwijderen: `systemctl --user disable --now tandemd`, daarna `~/.local/bin/tandemd` en
`~/.config/systemd/user/tandemd.service` weghalen.
