# Live video: screen and camera sessions

One mechanism carries three features. The phone screen in a window on the Mac, the Mac screen controlled from the Android
app (remote desktop), and the phone camera as a webcam. The core (`crates/tandem-core/src/live/`) moves encoded video and
the control around it; the apps capture, encode, decode and draw. This document is the contract between the core and the
apps. Where it and the code differ, the code wins and this document is wrong.

Names: the wire and the API say "media" (`MediaRequest`, `STREAM_MEDIA`, `media_push_frame`). The older `Msg::MediaPlayers`,
`MediaCommand` and `MediaArt` are the music feature and have nothing to do with this.

## The idea

A session has a **kind** (`screen` or `camera`), a **host** and a **viewer**.

- The **host** is the device that has the picture: the phone for its own screen or its camera, the Mac for its screen. Its
  app captures, encodes to H.264 or HEVC and pushes access units into the core.
- The **viewer** is the device that asks for it and shows it. Its app decodes what the core hands over and draws it.
- **Control** is optional and only for `screen`: the viewer sends pointer, key and text input back and the host app
  injects it. The host decides whether control is granted.

The viewer always starts the session (pull, like the files). The host answers. Either side can stop it.

```
 viewer app                  viewer core            host core                 host app
     |                           |                      |                        |
     | media_request(kind,...)   |                      |                        |
     |-------------------------->|  MediaRequest        |                        |
     |                           |--------------------->| policy check           |
     |                           |                      | on_request(...)        |
     |                           |                      |----------------------->| consent, start capture
     |                           |                      |      media_accept(...) |
     |                           |  MediaAccept         |<-----------------------|
     |      on_accepted(...)     |<---------------------|                        |
     |<--------------------------|                      |                        |
     |                           |                      |      media_push_frame  |
     |                           |   one uni stream     |<-----------------------|
     |        on_frame(...)      |<====  per frame  ====|                        |
     |<--------------------------|                      |                        |
```

## What a device announces

`Hello.caps` is how a device says what it can do. The apps put these in `TandemConfig.caps` (the core does not enforce
them, it only passes them on in `DeviceInfo.caps`):

| Cap | Meaning |
|---|---|
| `screen.host` | can share its own screen |
| `camera.host` | can share its camera |
| `screen.view` | can show the screen of another device (and send control) |
| `camera.view` | can show the camera of another device |

A request to a device that has no `MediaHost` registered is answered at once with `Unsupported`.

## Consent and policy

Consent is per session. The host core asks its app (`MediaHost.on_request`, also `Event::MediaRequested`) and the app shows
whatever it wants and answers with `media_accept` or `media_deny`. A request that nobody answers within 60 seconds is denied
with `Timeout`. The viewer waits 75 seconds.

A person can store a choice per device and kind, in the core, so a screen in the way cannot overrule it. `MediaPolicy`
(persisted in `media.cbor` next to `files.cbor`, same API shape as `FilePolicy`):

| Field | Values | Meaning |
|---|---|---|
| `screen` | `ask` (default), `always`, `never` | sharing this device's screen with that device |
| `camera` | `ask`, `always`, `never` | sharing this device's camera with that device |
| `control` | `ask`, `always`, `never` | letting that device control this device's screen |

- `never` for the kind: the core denies with `Policy` and the app never hears of it.
- `always` for the kind (and for `control` when the request wants control): the app is told `pre_approved = true`. It must
  not ask the person again, but it still has to start capturing and call `media_accept`, because only the app knows the codec,
  size and frame rate it can give.
- `control = never`: the request is not denied, control is simply not granted. `media_accept(control = true)` is clamped to
  `false` by the core.
- A device without its own choices uses the default policy (`media_default_policy`), the same as the files.

## On the wire

### Control messages (stream 1, CBOR, like every other `Msg`)

| Message | Direction | Fields |
|---|---|---|
| `MediaRequest` | viewer to host | `session` (random non-zero u64, chosen by the viewer), `kind`, `codecs` (preferred first: `h264`, `hevc`), `max_width`, `max_height`, `max_fps`, `max_bitrate` (bits per second, 0 = no preference), `control`, `facing` (`any`, `front`, `back`, for the camera) |
| `MediaAccept` | host to viewer | `session`, `kind`, `codec`, `width`, `height`, `fps`, `bitrate` (the starting target), `control` (granted) |
| `MediaDeny` | host to viewer | `session`, `reason` (a `MediaEnd`), `message` |
| `MediaUpdate` | host to viewer | `session`, `update`: optional `format` (`width`, `height`, `rotation` in degrees, `fps`) and optional `control` (revoked or granted later) |
| `MediaStop` | both | `session`, `reason` (a `MediaEnd`) |
| `MediaKeyframe` | viewer to host | `session`: send me a keyframe now |
| `MediaReport` | viewer to host | `session`, `interval_ms`, `frames`, `bytes`, `lost`, `dropped`, `jitter_us`: once a second |
| `MediaInput` | viewer to host | `session`, `input` (see below) |
| `MediaOffer` | host to viewer | `kind`, `facing`: the person started the sharing on the host itself and asks the viewer to look. Not a session: the viewer's app answers with an ordinary `MediaRequest`. A device that does not know it skips it |

`MediaEnd` is the one list of reasons for `MediaDeny` and `MediaStop`: `ended` (a person stopped it), `declined`, `policy`,
`busy`, `unsupported` (kind or codec not available), `unavailable` (no permission to capture, no camera), `timeout`,
`replaced` (the same peer started a new session of that kind), `unknown_session` (I never heard of it), `peer_gone`
(away for longer than the grace period), `error`. Unknown values read as `error`.

New fields always get `#[serde(default)]`. A message this version does not know is skipped, as everywhere else.

### Frames (one unidirectional QUIC stream per frame)

The host opens a uni stream per access unit. First byte `STREAM_MEDIA` (4), then a 28 byte header, big-endian, then the
payload and the end of the stream:

```
offset  size  field
0       1     header version, 1
1       1     flags: bit0 KEYFRAME, bit1 CONFIG (the payload starts with the parameter sets),
              bit2 DISCONTINUITY (frames were dropped before this one)
2       2     reserved, zero
4       8     session
12      4     sequence, +1 for every frame the app pushed (also for the ones the host dropped), wraps
16      8     pts in microseconds, on the clock of the host app, only differences count
24      4     payload length, at most 8 MiB
28      n     payload: one access unit in Annex B (start codes), H.264 or HEVC
```

Why a stream per frame: a lost packet delays only its own frame, never the next one (no head-of-line blocking between
frames), and the sender can cancel a frame by resetting its stream, so QUIC stops retransmitting what is no longer worth
having. Frames are reliable *until the host gives up on them*.

Priorities: the control stream is above everything (so a keyframe request or a key press is not stuck behind video), a
keyframe above other frames, video above file transfers. A big file copy does not stall the picture.

Parameter sets (H.264 SPS and PPS, HEVC VPS, SPS and PPS) travel with every keyframe: the core checks the access unit the
app pushes, and when a keyframe lacks them it puts the last ones in front. The app can hand them over with
`media_push_config` (what Android's `MediaCodec` gives as codec config) or just push keyframes that contain them (what
`KEY_PREPEND_HEADER_TO_SYNC_FRAMES` or Video Toolbox plus a small Annex B conversion give). A keyframe for which the core has
no parameter sets at all is refused (`MediaPush.Dropped`). Every keyframe on the wire is therefore decodable on its own,
which is what makes joining, recovering and resuming simple.

### Pointer position (datagram)

An absolute pointer position is worth nothing a few milliseconds later, so it travels as an unreliable datagram: kind byte 4,
session (u64), a counter (u16, older ones are ignored), x and y as u16 (0 to 65535 of the picture). Everything else of
`MediaInput` goes over the control stream because it must not get lost.

### Input

`MediaInput` (only accepted by the host when control was granted, otherwise dropped and not delivered):

| Variant | Meaning |
|---|---|
| `PointerAbs { x, y }` | position as a fraction of the streamed picture, 0.0 to 1.0, top left is the origin |
| `PointerRel { dx, dy }` | relative movement in pixels of the streamed picture |
| `Button { button, down, clicks }` | 0 primary, 1 secondary, 2 middle, 3 back, 4 forward. `clicks` is the click count of this press (1 single, 2 double, 0 counts as 1) |
| `Scroll { dx, dy }` | pixels of the streamed picture, natural direction: content follows the fingers, so a positive `dy` moves the content down |
| `Key { code, down, mods, text }` | `code` is the USB HID usage on the Keyboard page (0x04 is A, 0xE0 is left control), the same on every platform. `mods`: 1 shift, 2 control, 4 alt/option, 8 meta/command, 16 caps lock. `text` is what the key produced on the viewer, empty when it produced nothing |
| `Text { text }` | a committed piece of text (IME, dictation, paste typed out). Not the clipboard: the clipboard is never touched |

## Flows

### Start

```
viewer                       host core                       host app
  | MediaRequest ---------->   |
  |                            | circle member? limits? policy never? -> MediaDeny
  |                            | no MediaHost -> MediaDeny(unsupported)
  |                            | on_request(from, session, request, pre_approved) ->   consent
  |                            |   <- media_accept(session, codec, w, h, fps, bitrate, control)
  | <---------- MediaAccept    |
  | on_accepted                |
  | (frames start as soon as the app pushes them; the first one is a keyframe)
```

A second request of the same kind from the same peer replaces the first (the old one ends with `replaced`): a viewer that
crashed must not lock itself out. At most 8 sessions per device, one per kind per peer.

### Start from the host

The viewer always asks, so a person who starts the sharing on the phone itself ("Show my screen on the Mac") does it in
two steps: the phone's app gets the capture permission first and keeps it ready, then `media_offer(peer, kind, facing)`
sends a `MediaOffer`. The viewer's core emits `Event::MediaOffered`; its app opens a window and calls `media_request`.
The host's app recognises that request as the one it is waiting for (same peer, same kind) and answers it without asking
again. Nothing starts by itself: an offer that the viewer ignores just times out on the host. The apps only offer to a
device whose caps say `screen.view` or `camera.view`.

### Frames and what happens to them

The host app calls `media_push_frame` for every encoder output. The call never waits for the network. The core decides at
once, per frame:

- Waiting for a keyframe (start, or after something was dropped): a frame that is not a keyframe is dropped.
- Too much in flight (the oldest unacknowledged frame is older than `max(250 ms, 3 rtt)`, or more bytes in flight than the
  budget): the frame is dropped, and the stream is marked broken, so everything that follows is dropped until a keyframe, and
  `on_keyframe` asks the app for one. A slow link drops frames; it never builds latency.
- Otherwise it is queued for sending.

A frame that was sent but is not acknowledged within `max(400 ms, 4 rtt)` (a keyframe: `max(2 s, 8 rtt)`) is cancelled by
resetting its stream, and the stream is marked broken in the same way. A keyframe that goes out because the stream was broken
cancels every older frame still in flight: they are worthless once it arrives.

The viewer reads every frame stream to the end at once (the network is never blocked by a slow app), puts the frames in
order by sequence and gives them to the app in that order, one at a time, from a task of its own:

- A frame that completes before an earlier one waits for it, while the earlier one is still arriving. When the earlier one
  is reset by the host, or never shows up within `150 ms + rtt`, it is lost.
- After a loss the chain is broken: a P frame without its reference is garbage on screen, so nothing is delivered until the
  next keyframe. A freeze is better than damage. The viewer asks for a keyframe at once and again every 500 ms until one
  comes. A keyframe jumps over any gap; it never has to wait for the missing frames.
- The viewer stops (`STOP_UNWANTED`) a stream it does not need (a P frame while waiting for a keyframe) as soon as it has the
  header, so the host does not spend the bandwidth.
- If the app is too slow and the queue towards it is full (32 frames), the frame is dropped like a loss.

```
host                                               viewer
  K0 P1 P2 P3 [P4 lost] P5 P6 ... ---->            K0 P1 P2 P3  (delivered)
                                                   P5 arrives, P4 missing
                                                   after the wait: broken, P5 P6 discarded
                                         <---- MediaKeyframe
  on_keyframe -> app forces an IDR
  K7 (parameter sets + IDR, DISCONTINUITY) ---->   K7 delivered, the chain is whole again
```

### Bitrate

The viewer sends a `MediaReport` once a second: frames and bytes received, frames lost, frames it had to drop because the
app was slow, and the jitter. The host core combines that with what it sees itself (frames it dropped, how long
acknowledgements take) and moves a target bitrate:

- Trouble in an interval: down to 75% of the target, but not above 90% of what actually arrived, never below a tenth of the
  starting bitrate. After a decrease no increase for 4 seconds, 8 after the second one in a row, 16 at most.
- Quiet intervals: only after the hold-off and three quiet intervals in a row, and only while the link is actually in use
  (at least 60% of the target arrives), up by 8% at a time. From a quarter back to full takes about a minute.
- `on_bitrate(session, bits_per_second)` is called when the target moved by 5% or more, and always for a decrease. The app
  applies it to its encoder (`MediaCodec` `PARAMETER_KEY_VIDEO_BITRATE`, `VTCompressionSession` average bit rate). It is
  also the event `Event::MediaBitrate`.

The target never goes above the `bitrate` the host accepted with. The app may of course also lower the frame rate or the
resolution when it hears a low value.

### Input

```
viewer app --media_send_input--> viewer core --MediaInput / datagram--> host core --on_input--> host app
```

`media_send_input` on the viewer fails with `ControlNotGranted` when the host did not grant control. The host core drops
whatever arrives anyway. The host can take control away or give it later with `media_update(control)`; the viewer hears it
through `on_update`.

### Stop

Either side calls `media_stop`. The other side gets `on_ended` / `on_stop` with the reason. The host core stops accepting
frames at once (`media_push_frame` answers `NoSession`), cancels what is in flight and frees the slot. A `MediaStop` for a
session that is already gone is ignored; any message about a session the receiver does not know is answered once with
`MediaStop(unknown_session)`, so a side that lost its state ends the other one cleanly.

### A connection that drops or switches

Sessions belong to the peer, not to a QUIC connection.

- A new connection that replaces the old one (a network switch, the duplicate rule) is invisible: frames in flight on the old
  one are lost, so the host marks the stream broken and the viewer asks for a keyframe; the next keyframe heals it.
- When the connection is gone, the session is **suspended**: it stays for 10 seconds. `media_push_frame` drops everything
  (`Dropped`). When the device is back, the viewer sends a `MediaKeyframe` for each suspended session and picture resumes with
  that keyframe. Both apps get no `ended`, they may show a spinner (`Event::Disconnected` / `Connected`).
- After 10 seconds without a connection the session ends on both sides with `peer_gone`. The same goes when the peer
  restarted (a different `boot_id` in its `Hello`): its sessions are gone.
- With a live connection but a viewer that has gone silent (no report for 15 seconds) the host ends the session with
  `peer_gone`.

## Limits

| What | Limit |
|---|---|
| Sessions | 8 per device, one per kind per peer |
| Frame | 8 MiB payload, a stream that stays unfinished for 5 seconds is dropped |
| Frames in flight (host) | by age and bytes, see above; at most 64 |
| Frames waiting for an earlier one (viewer) | 32 |
| Frames queued towards the viewer app | 32 |
| Concurrent uni streams | 128 (`tls.rs`) |
| Request wait | host 60 s for the app, viewer 75 s |
| Grace period for a dropped connection | 10 s |
| Silent viewer | 15 s without a report |

Only circle members can reach any of this: the connection is refused before anything is processed otherwise, and every
message and stream is tied to the peer the session was made with. A peer cannot touch a session of another peer.

## The API

Rust (`tandem_core::live`, methods on `Engine`; all of them return at once and may be called from any thread within the
runtime): `media_request`, `media_accept`, `media_deny`, `media_stop`, `media_push_frame`, `media_push_config`,
`media_update`, `media_send_input`, `media_request_keyframe`, `media_sessions`, `media_stats`, `set_media_host`,
`set_media_viewer`, and the policy functions `media_policy`, `has_own_media_policy`, `media_default_policy`,
`set_media_policy`, `clear_media_policy`, `set_media_default_policy`.

### Host app (Kotlin; the Swift names are the same)

```kotlin
class ScreenHost : TandemMediaHost {
    // 1. A request. Ask the person unless preApproved, then start capturing and encoding and accept.
    override fun onRequest(from: String, session: ULong, request: TandemMediaRequest, preApproved: Boolean) {
        engine.mediaAccept(session, TandemMediaAccept(codec = TandemMediaCodec.H264, width = 1080u, height = 2400u,
            fps = 60u, bitrate = 8_000_000u, control = false))   // or engine.mediaDeny(session, TandemMediaEnd.DECLINED)
    }
    // 2. Every encoder output. Never blocks. Tells you what happened to the frame.
    fun onEncoded(session: ULong, data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        engine.mediaPushFrame(session, data, ptsUs.toULong(), keyframe)   // Sent, Dropped, WaitingForKeyframe, NoSession
    }
    override fun onKeyframe(session: ULong) { encoder.requestSyncFrame() }       // MediaCodec / VTCompressionSession force IDR
    override fun onBitrate(session: ULong, bitsPerSecond: UInt) { encoder.setBitrate(bitsPerSecond) }
    override fun onInput(session: ULong, input: TandemMediaInput) { /* inject, only comes when control was granted */ }
    override fun onStop(session: ULong, reason: TandemMediaEnd) { /* stop capturing */ }
}
engine.setMediaHost(host)
```

The callbacks run on a core thread: queue the work and return. When the size or orientation of the picture changes, call
`engine.mediaUpdate(session, TandemMediaUpdate(width = ..., height = ..., rotation = ..., fps = null, control = null))`
before the first frame of the new size. The first frame after the update must be a keyframe.

### Viewer app

```kotlin
class ScreenViewer : TandemMediaViewer {
    override fun onAccepted(session: ULong, accept: TandemMediaAccept) { /* configure the decoder from codec, width, height */ }
    override fun onUpdate(session: ULong, update: TandemMediaUpdate) { /* new size, control revoked or granted */ }
    override fun onFrame(session: ULong, ptsUs: ULong, keyframe: Boolean, discontinuity: Boolean, data: ByteArray) {
        // Always in order, always decodable from the first keyframe on. Queue to the decoder and return.
        // discontinuity: flush the decoder before this keyframe.
    }
    override fun onEnded(session: ULong, reason: TandemMediaEnd) { }
}
engine.setMediaViewer(viewer)
val session = engine.mediaRequest(peer, TandemMediaWant(kind = SCREEN, codecs = listOf(H264, HEVC), maxWidth = 3840u,
    maxHeight = 2160u, maxFps = 60u, maxBitrate = 20_000_000u, control = true, facing = ANY))
engine.mediaSendInput(session, TandemMediaInput.PointerAbs(0.5f, 0.5f))
engine.mediaRequestKeyframe(session)     // when the decoder choked
engine.mediaStop(session)
```

`engine.mediaStats(session)` gives counters for a debug overlay: frames in and out, drops by reason, losses, keyframe
requests, bytes, the current target bitrate, frames and bytes in flight, jitter, the age of the last frame and the rtt.
`engine.mediaSessions()` lists the sessions with their role and state.

## tandemd, to prove a link on real machines

```
tandemd media-test <device> screen|camera            view: ask the device for synthetic frames and print what arrives
tandemd media-test <device> screen|camera --host     host: answer requests from that device with synthetic frames
```

The frames are not video, they are Annex B shaped bytes with a counter and a checksum in them, at 30 fps and 4 Mbit by
default (`--fps`, `--bitrate`, `--seconds`). The viewer prints every second what it received, what was lost, the keyframe
requests, the target bitrate and the age of the last frame, and checks every frame for integrity and order. Both sides run
their own engine, so stop a running `tandemd run` on that machine first (or give `--port 0` and `--data-dir`).
`--loss N` on the host drops every Nth frame on the wire, to see the recovery.

## What is not covered here

Capture, encoding, decoding, drawing, input injection and the permissions that go with them (`MediaProjection`,
`ScreenCaptureKit`, Accessibility, the camera) are the apps. Audio of the camera or the screen is not part of this.

## Android as the host of control

A Mac that asks for the screen of a phone also asks for control (`control` in `MediaRequest`). The phone grants it only when its
accessibility service (`TandemAccessibilityService`) is running, and the core still clamps it by the policy. Input then arrives
as `MediaInput` and is turned into gestures by `RemoteInput`: the left button is a finger (press and release in one place is a
tap, held longer a long press, moved a swipe), the right button is Back, the middle button Home, back and forward are Recents,
a scroll is a swipe in the direction of the content, text goes into the focused field and Escape is Back.

Capabilities: `screen.host` (can share its screen), `screen.control` (a Mac that has Accessibility on, so a phone can click),
`screen.view` (can show another device's screen), `camera.host`, `camera.view`.

## Een tweede scherm (0.1.80)

Een apparaat kan een computer vragen om een scherm van zichzelf te maken en dat als tweede scherm te gebruiken: niet een kijkje op het scherm dat
de computer heeft, maar een beeldscherm erbij, met precies zoveel pixels als het apparaat heeft, waar je vensters naartoe sleept. Het verzoek is
een gewoon schermverzoek met `extend = true` (in `MediaRequest`, `serde(default)`, dus een oudere computer doet er niets mee en toont zijn eigen
scherm; daarom meldt een computer die het kan de capability `screen.extend`, en zonder die capability biedt de app de keuze niet aan).
`max_width` en `max_height` zijn dan de pixels van het apparaat in de stand waarin het wordt gebruikt (liggend).

- **Mac als host** (`ScreenHost`, `VirtualDisplay`, `Sources/TandemVirtualDisplay`): de Mac maakt een beeldscherm van software met de klassen
  `CGVirtualDisplay` van CoreGraphics (er is geen openbare header, het Objective-C-doel in het pakket declareert wat er is en roept ze alleen aan
  als `NSClassFromString` ze vindt). Het scherm komt rechts van het hoofdscherm, in de gevraagde grootte (daarvoor wordt de modus met de hand
  gekozen, want het systeem kiest eerst een kleinere), en verdwijnt als de sessie eindigt. De opname (`ScreenCapturer`) is die van dat scherm in
  plaats van het hoofdscherm, en de invoer van het apparaat wordt op het gebied van dat scherm gezet (`RemoteGeometry`). Gezien op een echte Mac:
  het scherm komt op, in de maat, op de plek, en is weg na het stoppen. Niet gezien: de opname ervan (die vraagt het recht Schermopname, dat
  het testprogramma niet heeft).
- **Android als kijker**: de rij "Use as second screen" op de pagina van een Mac die `screen.extend` meldt. De telefoon gaat liggen, vraagt zijn eigen
  pixels, en raakt aan = klikken waar je raakt (direct aanraken). Gezien op de emulator tegen een nagebootste Mac (`tandemd --pretend-screen`, die nu ook
  `screen.extend` meldt en het verzoek afdrukt).
- **Nog niet**: Windows, Linux en Mac als kijker (een laptop als tweede scherm; daar moet het decoderen soepel genoeg zijn voor 60 beelden per seconde,
  wat nu het probleem is op Linux), en Linux (GNOME kan een virtuele monitor maken met de ScreenCast-portal) en Windows (vraagt een stuurprogramma) als host.
