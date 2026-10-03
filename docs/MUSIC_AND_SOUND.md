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
  Settings, General shows which of the two Tandem sees right now.
- **Covers from the Mac.** The announcements carry no picture. A Spotify cover is asked of Spotify's
  public embed endpoint (`open.spotify.com/oembed`) by the id of the track: no account, no
  permission, and only an image from Spotify's own servers is taken. Besides the update check it is
  the one place the Mac app talks to a server outside the circle, and the switch for music stops it.
  A cover from Music is asked of Music with Apple Events, converted to a small JPEG (Music hands out
  TIFF), and only once Tandem is already allowed to control Music: it never opens the permission
  question by itself. The player is sent with the key of its cover once the cover is in hand, and the
  cover goes in `Msg::MediaArt`, once per phone.
- **Buttons on the phone.** The phone's player for the Mac has the trackpad's buttons: mute, volume
  down, previous, play or pause, next, volume up. Previous, play and next are `Msg::MediaCommand`
  for that player (Apple Events on the Mac). Volume and mute are the Mac's own, sent as the media keys
  of `Msg::Input`, the same ones the trackpad sends, so they need the Accessibility permission on the
  Mac just like the trackpad does.

### In the system's Now Playing (Mac)

The player of a phone is also published to macOS as a Now Playing item (`MPNowPlayingInfoCenter`, with
the remote commands play, pause, toggle, next, previous and change position from
`MPRemoteCommandCenter`), so the media keys of the keyboard and of headphones, Control Center and any
app that shows what plays, a notch app say, see it and control it. Tandem plays no audio for this: mediaremoted
accepts an app that publishes a playing item as the active Now Playing app (checked in its log, where the active
client changes from the running music app to the publisher), and sends the keys to its handlers, which send
the matching `Msg::MediaCommand` on to the phone. Only a player that is worth showing is published (not one
that plays what this Mac already plays), and one that stays paused for five minutes is let go, so the play
key goes back to whatever this Mac played last. Settings, General has a switch.

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

### The phone in the system's sound outputs

A real output device needs a driver (an administrator installs it, and a mistake in it can silence the
whole Mac), so there is none. Instead, Tandem makes one public aggregate device per phone, `Name (Tandem)`,
with the Mac's own speakers as its only member. The system lists it like any output, in System Settings,
Sound and in the sound menu of Control Center (checked: the system reports such a device as an output
that can be the default and is not hidden).

The switch on the phone's page, Use this phone as a speaker, is on by default and stays on: it only decides
whether the phone is offered. Tandem watches the default output. When one of these is chosen it starts the
sound: a Core Audio tap on everything this Mac plays, the Mac muted, sent to the phone. When something else is
chosen it stops. A Use now button on the page, and the speaker button in the small menu, do the same by
choosing the output for you.

- Without Tandem running, or with the phone away, the output simply plays on the speakers, so choosing it never
  leaves the Mac silent. If the phone drops away or the Mac sleeps while its output is chosen, the sound moves
  to the phone again when it is back.
- macOS shows no volume slider for a device made this way; the volume is the phone's.
- The capture takes its clock from the Mac's speakers, not from the output made for the phone, because one
  aggregate cannot be part of another.
- The devices are removed when Tandem quits and when a phone is removed or switched off, and made again the
  next time. If one is left behind (a crash), the next start clears it, or delete it in Audio MIDI Setup.

## Seeking

The progress bar of a player can be dragged, on the Mac and on the phone: it follows the pointer, shows
where it would land and the time, jumps there when let go, and holds that place until the other device
reports the new position. A click or tap is a jump too. The command is `MediaCommand` with `Seek` and a
position; Spotify and Music are asked with `set player position`, a phone's session with `seekTo`.

## Not tested on hardware

The duplicate rule, the datagram format and the jitter buffer have tests, and the sound conversion
was run on hand made buffers. The capture itself, the permission prompt, the Apple Events and
the Spotify and Music announcements have only been compiled: they have not run on a Mac with sound.
