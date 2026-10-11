# Clipboard and notifications over Bluetooth

For when the Mac has no network to reach the phone on. It sits on the same GATT service as
the hotspot request (see HOTSPOT.md) and needs no new permission.

## Who talks to whom

The phone is the GATT server and advertiser, the Mac the central, as for the hotspot. The Mac
only looks for the phone, and only keeps a link, while the phone has been out of reach over the
network for two looks in a row (six seconds), and only opens a link to a phone it already shares
a key with.

### Seeing the phone

The scan reports every sighting of the service, not only the first. The phone's tag (the hourly
hint) rides in the scan response, which is not always in the first sight of it, and a scan that
reports a device once would never see it. A sighting with the right tag means the phone is in
range: the Mac shows it as "Over Bluetooth" and offers to ask it for its hotspot, whether or not
the two share a key yet. A phone that never shows a tag is tried after five seconds when a link
is allowed, and let go again after fifteen if no frame from it opens with the phone's key; such a
beacon is then left alone for ten minutes, so a stranger's phone is not tried every few seconds.
A beacon not seen for fifteen seconds is out of range.

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

## Between computers (0.1.82)

A phone is the one that waits, as above. Two computers (Mac and Linux so far; Windows has no Bluetooth code yet) do the same with
each other, and a Linux computer also looks for a phone like the Mac does. Of two computers the one with the **lower id looks and
connects**, the other advertises the service and waits: the lower id is also the one that makes the key (`Msg::BleKey`), so there is one
link and not two. The core has `ble_is_central_for(id)` for it. A computer that can take part says `ble.link`, so nobody looks for an
older version that does not wait.

- **Mac**: `BlePeripheralHub` (CBPeripheralManager) serves the service and advertises it, `BleMessenger` looks (one for each computer that
  this Mac is the one to connect to, started by `startBleWatch` once the computer has been out of reach for two looks in a row).
- **Linux**: `winsys/src/ble.rs` talks to BlueZ on the system bus (zbus): it scans with a filter on the service, connects, switches on
  the notifications of `msg_out` and writes to `msg_in`; and it serves the same service as a GATT application with an advertisement.
  `src/bluetooth.rs` decides when, like the Mac does, and the setting "Messages over Bluetooth" turns it off.
- **No tag from a computer.** CoreBluetooth cannot put service data in an advertisement, and BlueZ has no room for it next to the service
  (31 bytes), so a computer advertises only the service. The one that looks takes it for a candidate after five seconds and believes it
  only when a frame from the right device opens; one that does not is left alone for ten minutes. A phone still has its tag.
- A notification goes to everybody who switched them on (BlueZ has no way to aim one); frames are sealed for one device, so the others
  cannot open them and drop them.

Not tested on hardware: none of this has run between two real computers. The core's tests cover the sealing and the roles, the BlueZ
helpers have tests, and the Mac and Linux code compile; a link between a Mac and a Linux laptop has not been seen.
