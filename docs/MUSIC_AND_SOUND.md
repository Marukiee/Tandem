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

The Mac can play its sound on a phone, which then is its speaker. Every other pair works too, see "Every device the speaker of
every other" below.

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

## Het geluid van een telefoon op de Mac

De andere kant op werkt met dezelfde berichten: de telefoon stuurt `AudioStart` met stream `9` (de Mac gebruikt een eigen
streamnummer naar de telefoon), daarna datagrammen met 16 bit samples, en sluit af met `AudioStop`. De Mac meldt dat
hij het kan met de mogelijkheid `audio.play`. Op Android neemt `SoundShareService` het geluid van andere apps op met
AudioPlaybackCapture (toestemming voor opnemen en de vraag van het systeem over schermopname, elke keer tenzij Shizuku de app-op
heeft gezet, zie "Without the screen recording question" hieronder; apps die opname
niet toestaan blijven stil). Op de Mac speelt `PhoneSound` het af: een korte buffer (`SoundRing`) met een kussen vooraf,
stilte op de plaats van een verloren pakket, oud geluid weg als de klokken uit elkaar lopen, en een audio-engine die
op het tempo van de luidsprekers trekt. De vertraging heeft drie standen (laag, normaal, soepel) zoals op de telefoon.
De buffer heeft een zelftest: `TANDEM_DEBUG_SOUNDRING=1` met `TANDEM_DEBUG_DIR` schrijft `soundring.txt`.

## Every device the speaker of every other

Any device can send its sound to any other that plays sound, and a device can play the sound of several at once. The
messages are the ones above (`AudioStart`, datagrams, `AudioStop`); nothing new goes over the wire but two capabilities:
`audio.play` (plays what others send, any stream number, now on every app) and `audio.send` (can record and send its own).

- **The button** is on the page of the device that should play, on the sender: Mac "Use as speaker" for a computer (a phone
  keeps Phone as output and its sound menu entry), Linux and Windows "Use as speaker" for any device, Android "Send this
  phone's sound to". Pressed again it stops. It shows for a device that announces `audio.play`, and for any phone, since
  older phones play it without saying so. One output per sender: a new one takes the place of the last, and so does a
  device that looks at the screen and asks for the sound.
- **Stream numbers.** A computer that sends uses `9`, the number every player so far takes (older Macs and Linux play
  only that one). The Mac towards a phone keeps a new number for each start, skipping `9`.
- **Never both ways with the same device.** A device does not play the sound of a device it sends to, and does not send
  to one whose sound it plays: it says no with a stop, or tells its person why. That keeps a stop unambiguous (it ends
  the one direction there is) and keeps two computers from sending each other's sound round and round.
- **A no from the player** is a stop right after the start: the sender ends and tells its person that the other device
  does not play sound from other devices (or, later, that it was stopped there). Players say no when their switch is
  off (Mac: Sound from other devices; Android: Speaker for your other devices; Linux and Windows: Play the sound of other
  devices here), or when the sound card cannot be opened.
- **Recording.** Mac: the process tap, now on every process but Tandem, so what Tandem plays for other devices is not
  sent on, and is not muted with the rest. Linux: `parec` of the monitor of the default output, else GStreamer
  `pulsesrc` (PulseAudio or PipeWire with its Pulse server); a recorder that ends before it delivered anything is told to
  the person as "cannot record". Windows: WASAPI loopback of the default output through cpal. Android: playback capture
  with the screen recording question each time (unless Shizuku set the app-op, see below), Tandem's own uid left out; only apps that allow capture are heard, which
  the phone says when it starts.
- **Quiet at the sender.** Only the Mac goes quiet (the tap mutes what it records, Settings, General). Linux, Windows and
  Android keep playing and say so when it starts: muting the output may silence the monitor or the loopback too, and
  whether it does differs per sound system and could not be tried here. A null sink on Linux, which takes the streams
  away from the speakers, is the way to add it there.
- **Playing several.** Mac: an audio engine per sender, each with its own `SoundRing`. Android: an `AudioTrack` and a
  `JitterBuffer` per sender, one audio focus and one notification with all the names. Linux and Windows: a cpal stream
  and buffer per sender, with the same delay setting (`sound_delay`). The system mixes them; without a sound server on
  Linux a second stream may find the card taken, and that sender gets a no.
- **Not tried on hardware.** Everything here is compiled and the existing buffer tests pass; no sound went between real
  devices. A ring of three devices through Linux or Windows (which record their own playback) could still echo.

## Without the screen recording question

Android asks its screen recording question each time a phone's sound or screen is shared. Tandem avoids it where Android allows,
and picks the way by itself (`PhoneSound` on the phone), behind the same button and the same request from a computer that looks at
the phone's screen. A way that fails falls through to the next without a word; only the failure of the last one is told.

1. **Shizuku.** When Shizuku is running and Tandem is allowed, Tandem sets `appops set <package> PROJECT_MEDIA allow` once, through
   its Shizuku user service (`ShellUserService`, which runs as the shell or root), for the package of the running build. It is set
   again whenever Shizuku comes up and checked at every start of the app (so after an update too); the mode is read from the system,
   never remembered. With it allowed, `MediaProjectionManagerService.hasProjectionPermission` is true, and SystemUI's
   `MediaProjectionPermissionActivity` answers the capture intent at once with a projection of the entire screen, before the
   question and before the choice between one app and the entire screen (read in AOSP, Android 14, 15 and 16). The intent is still
   launched and closes right away, so Tandem has to be in front: a request from a computer while Tandem is behind still comes as a
   notification to tap. The notification that the sound or screen is shared stays. This works for the sound and for showing the
   screen, to every device, the Mac included.
2. **The computer as Bluetooth speaker.** Without Shizuku, to a Linux or Windows computer that says `audio.bt_sink` and is paired
   with the phone over Bluetooth: the phone sends `BtSpeaker` and the computer connects to the phone's Audio Source (A2DP) from its
   own side, so the phone needs nothing, not even the permission to record. Everything the phone plays goes there, also apps that
   do not allow capture, and it no longer plays on the phone itself. Linux: BlueZ over D-Bus, `Device1.ConnectProfile` with
   `0000110a-...`; the adapter must list the Audio Sink UUID `0000110b-...`, which it does once PipeWire (WirePlumber) offers its
   `a2dp_sink` role. WirePlumber's configuration is only read, to say why when it is off. Stop is `DisconnectProfile` of that one
   profile. Windows 10 2004 and later: `AudioPlaybackConnection` (`GetDeviceSelector`, `TryCreateFromId`, `StartAsync`, `OpenAsync`),
   kept open on a thread of its own. **The phone is matched by name**: Android gives apps no Bluetooth address, so the phone sends its
   Bluetooth name (or its Tandem name without the Bluetooth permission) and the computer looks for a paired device by that name. A
   computer refuses when its switch for the sound of other devices is off, and when it sends its own sound to that phone (it would
   loop). A Mac cannot be an A2DP sink (see PARITY.md).
3. **Otherwise** the capture with the question, as before.

Settings, Shizuku on the phone shows the state of Shizuku (not installed, not running, needs permission, ready as root or as the
shell) and a check for each use: the hotspot that turns on by itself, and sound and screen without the question.

Seen on an emulator (Android 16) with `tandemd --pretend-cap`: with the app-op set by hand through adb (the same command the Shizuku
service runs, as the same shell user), the sound and the screen started without any question and the settings page showed the check;
with it back on default, a computer that says `audio.bt_sink` and does not answer made the row say "Connecting over Bluetooth" for
fifteen seconds, after which the question came as before. Not tried on hardware: Shizuku itself, BlueZ and `AudioPlaybackConnection`
have only been compiled, and no sound went over Bluetooth.

