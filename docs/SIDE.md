# Finding the side by sound

How a computer finds out on which side of it another device stands (`crates/tandem-core/src/side.rs`), and when it checks again by itself
(`side_check.rs`).

## Two kinds of sound

- **Audible**, when the person asks ("Find by sound", "Find automatically" in the question for a second screen): a sweep from 400 Hz to
  6 kHz, up on the left speaker and down on the right one at the same time, three times. Soft first, louder when it was not heard.
- **Quiet**, for a check that runs by itself: the same up and down sweeps between 18.5 and 20 kHz, 0.25 s each, three times, at 0.04 of
  full scale, with fades over a fifth of each sweep. Above what most grown-ups hear, and a test makes sure that the fades leave less
  than -70 dB of it below 15 kHz (a click is what would be heard). `SideListen` says which kind comes (`quiet`), and the listener reads
  only that one: music or a voice in the room can look more like the audible sweeps than the quiet ones do from across a desk (in the
  measurement below the room itself scored 20 dB on the audible filter). An older listener reads only the audible kind, hears
  nothing, and the side stays. A quiet check moves a screen only from a 3 dB difference (1.5 dB for
  the audible one) and when a second quiet check right after it agrees.

Why 18.5 to 20 kHz: below 18 kHz young people hear it; above 20 kHz a recording at 44.1 kHz loses it in the filter in front of the
converter (that starts near 0.45 of the rate, about 19.8 kHz), and the MacBook Pro speakers drop 15 dB between 20.5 and 21 kHz.

## Measured on a MacBook Pro (M2 Max, 24 October 2026)

Its own speakers into its own microphone, 48 kHz, system volume at 69 %. Speaker and microphone sit in the same case, so this is a
lot closer than a phone next to the laptop: it shows what the speakers and the microphone still carry, not the margin at a distance.

Chirps of 1 kHz wide, 0.2 s, at 0.01 of full scale (-40 dBFS), matched filter against the same filter over silence:

| Band (centre) | Left over noise | Right over noise | Left minus right |
|---|---|---|---|
| 1 kHz | 54 dB | 38 dB | +16 dB |
| 4 kHz | 69 dB | 59 dB | +9 dB |
| 16 kHz | 89 dB | 57 dB | +32 dB |
| 18 kHz | 86 dB | 61 dB | +25 dB |
| 19 kHz | 88 dB | 68 dB | +20 dB |
| 20 kHz | 88 dB | 72 dB | +16 dB |
| 20.5 kHz | 89 dB | 70 dB | +19 dB |
| 21 kHz | 72 dB | 67 dB | +5 dB |
| 21.5 kHz | 67 dB | 66 dB | +2 dB |

The quiet pattern itself, read by the core (the ignored test `a_real_room`), at its old level of 0.06 and lower:

| Level | Heard as | Left minus right | Above the room |
|---|---|---|---|
| 0.06 | quiet, left | +16.8 dB | 45 dB |
| 0.006 | quiet, left | +17.1 dB | 45 dB |
| 0.0006 | quiet, left | +14.6 dB | 44 dB |
| silence | not heard | | |

So 0.04 leaves more than 30 dB for distance, a microphone that faces away and a lower volume. The microphone of this MacBook sits on
its left, which is why left always wins here.

To measure in another room or on another device: `TANDEM_SIDE_WRITE=quiet.f32` writes the pattern (stereo, 32-bit floats, 48 kHz),
play it while recording one channel at 48 kHz into a file of the same format, and read it with
`TANDEM_SIDE_READ=rec.f32 cargo test -p tandem-core --release --lib a_real_room -- --ignored --nocapture`.

## When it checks by itself

Only on a computer with "Check the side by itself" on (the default), and only for a device whose side counts: one with an extra screen
of this computer, or one that sits left or right of it for the shared mouse. Never for one put above or below by hand, and a check
never puts a device somewhere it was not.

| What happened | Checks when the last check was at least |
|---|---|
| An extra screen starts, the device was moved | 20 s ago |
| The device connects, the computer wakes | 2 min ago |
| The pointer goes over to it, every minute while its extra screen runs | 2 min ago |

After a check that found no side (not heard, unsure, the device did not get ready) the wait doubles from 2 minutes up to 40, except
for a screen that starts or a move. There is no check when this computer's sound is off, goes to headphones, Bluetooth or AirPlay, or
is sent to another device, nor when the microphone on the other side is in use or not allowed (the listener then never says it is
ready, so nothing is played). A side that changed moves the screen and the edge of the mouse once the pointer is not on that device,
with one quiet toast.
