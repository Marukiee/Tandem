These are the protobuf definitions of Quick Share (Nearby Share) and of the UKEY2 handshake and secure messages under it.
Sources, all Apache License 2.0, copied unchanged except one import path in wire_format.proto:

- offline_wire_formats.proto, wire_format.proto and sharing_enums.proto: github.com/google/nearby (connections/implementation/proto,
  sharing/proto and proto)
- ukey.proto, securegcm.proto and device_to_device_messages.proto: github.com/google/ukey2 (src/main/proto)
- securemessage.proto: github.com/google/securemessage

Only the wire format is shared. The code that speaks it is written for Tandem (see docs/QUICKSHARE.md).
