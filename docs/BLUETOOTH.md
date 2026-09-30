# Clipboard and notifications over Bluetooth

For when the Mac has no network to reach the phone on. It sits on the same GATT service as
the hotspot request (see HOTSPOT.md) and needs no new permission.

## Who talks to whom

The phone is the GATT server and advertiser, the Mac the central, as for the hotspot. The Mac
only keeps a link while the phone has been out of reach over the network for two looks in a row
(six seconds), and only to a phone it already shares a key with.

Two characteristics on the service `6f2d7a10-8b1c-4e6f-a3d5-1c9e5b7f2a40`:

| UUID | Name | Direction | Use |
| --- | --- | --- | --- |
| `6f2d7a14-...` | `msg_in` | Mac to phone, write | pieces of a sealed frame |
| `6f2d7a15-...` | `msg_out` | phone to Mac, notify | pieces of a sealed frame |

## The key

The device with the lower id makes 32 random bytes the first time the two connect over QUIC and
sends them in `Msg::BleKey` on that authenticated connection. The other device stores them.
Nothing about the key ever travels over the air. Until two devices have connected once, there is
no Bluetooth channel between them.

## A frame

```
version (1) | sender id (16) | nonce (12) | ChaCha20-Poly1305 ciphertext and tag
```

Each direction has its own key, derived with BLAKE3 from the shared key and the two ids
(sender, then receiver), and the ids and version are also the associated data. A frame cannot be
turned round and played back to its sender.

Inside is a CBOR `Msg` and a sequence number. The sender keeps its number climbing (the clock, or
one more than the last), and the receiver stores the highest it accepted and refuses anything at
or below it, so a captured frame cannot be replayed, also not after a restart.

Only clipboard, notification (post, remove, action) and ping messages may cross the air. A frame
above 48 KB is not sent.

## Chunks

A frame is cut into writes that fit one packet: `message id (1) | index (1) | count (1) | bytes`.
The core cuts and puts back together (`ble.rs`); the apps only move chunks. A chunk with a new
message id throws away a half-finished older one.

## Sequence on a new link

1. The Mac connects, finds both characteristics, switches on notifications for `msg_out`.
2. The Mac writes its hello (a sealed ping). When it opens, the phone knows which device the link
   belongs to and answers with its own hello, so the Mac knows too.
3. Both sides poll the core for queued frames every 400 ms and write them. A message for a device
   with no connection but a live link is queued by the core instead of failing.

## Not tested on hardware

The sealing, chunking, replay refusal and key exchange are covered by the core's tests. The GATT
and CoreBluetooth code has only been compiled: it has not run between a phone and a Mac.
