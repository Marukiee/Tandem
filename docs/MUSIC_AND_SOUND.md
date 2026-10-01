# Music and sound

Two features that share one idea: a device shows and controls what another plays.

## Music

Each device tells the others what it can play, as a whole list every time something changes:
`Msg::MediaPlayers` (id, app, title, artist, album, playing, position, length, which buttons work,
and the key of a cover). A cover goes once per track in `Msg::MediaArt`, apart from the list, so a
progress update never carries a picture. The other device presses buttons with `Msg::MediaCommand`
(play, pause, toggle, next, previous, seek). The list and the commands may also go over Bluetooth
(see BLUETOOTH.md); covers may not.

- **Phone.** The players are the system's media sessions, which every music and video app publishes
  for the lock screen. Reading them needs notification access, the permission notification mirroring
  already asks for. Apps can be switched off (Settings, Music and sound, Choose apps), and one switch
  stops everything in both directions.
- **Mac.** Spotify and Music announce every track and every play or pause to anyone listening, with
  no permission. Pressing their buttons uses Apple Events and asks once under Privacy and Security,
  Automation. Other players (a browser tab, a video app) are not shown: macOS keeps them from apps.

### One player, not two

A device leaves out a player of the other device that plays what one of its own already plays: the
same title, an artist that fits (one side often lists more of them), and, when both know it, a length
within three seconds. The app does not matter. A phone that only remote controls the Mac's Spotify
says the same track in a different app name, and showing it would give two Spotify players. The rule
is `media::visible` in the core, used by both apps, with its own tests.

## Speaker

The Mac can play its sound on a phone, which then is its speaker.

- **Control** goes over the normal connection: `Msg::AudioStart` (sample rate, channels, a stream
  number) and `Msg::AudioStop`, which either side may send. The phone refuses by sending a stop.
- **Sound** goes as QUIC datagrams, because a late packet is worth nothing and retrying it would only
  add delay: `kind (3) | stream | counter (u32) | 16 bit signed samples, interleaved`, each cut to
  fit the path (about 1100 bytes, a few milliseconds). It skips the event channel and goes straight to
  the app, so a slow app cannot make it, or anything else, lose events.
- **Mac.** A Core Audio process tap on every process, in a private aggregate device (macOS 14.2 and
  later), with the Mac muted while it runs. The first start asks for System Audio Recording. Without it
  the tap delivers silence and macOS offers no way to ask whether it has the permission. A second of
  silence stops the sending, so a Mac that plays nothing sends nothing. A capture that goes quiet
  because its output disappeared is opened again on the new one.
- **Phone.** A jitter buffer (`JitterBuffer`) holds about 100 milliseconds, drops what comes too late,
  fills a lost packet with silence so the rest does not slide, and throws old sound away when the two
  clocks drift apart. An `AudioTrack` in low latency mode plays it, with audio focus, and a
  notification with a Stop button shows while it plays. Settings, Music and sound, Speaker for your Mac
  switches it off.
- **Cost.** 48 kHz stereo is about 1.5 Mbit/s, about 700 MB an hour. The delay is roughly a tenth of
  a second plus the network: fine for music, noticeable for video.

## Not tested on hardware

The duplicate rule, the datagram format and the jitter buffer have tests, and the sound conversion
was run on hand made buffers. The capture itself, the permission prompt, the Apple Events and
the Spotify and Music announcements have only been compiled: they have not run on a Mac with sound.
