//! What the Bluetooth hotspot request signs, and the hint a phone advertises.
//!
//! Both live in the core so the Mac and the phone build exactly the same bytes. A
//! disagreement between two hand-written copies would show up as a request that is
//! always refused, on real hardware only.

use ring::digest;

use crate::ids::DeviceId;

/// Domain separation: a signature made for this purpose is worthless anywhere else,
/// and a signature made for another purpose (a circle statement) is worthless here.
pub const AUTH_PREFIX: &[u8] = b"tandem-hotspot-v1";
pub const CHALLENGE_LEN: usize = 16;

/// Length of the hint in the advertisement. The scan response has room for 13 bytes
/// next to the 128-bit service UUID, and 8 is what mDNS uses too.
pub const BLE_HINT_LEN: usize = 8;

pub const ACTION_ON: u8 = 1;
pub const ACTION_OFF: u8 = 2;

/// The exact bytes the Mac signs and the phone verifies.
///
/// The timestamp is signed as well so it cannot be swapped afterwards. Freshness does
/// not depend on it: clocks drift, and the single-use challenge already stops replay.
pub fn auth_message(challenge: &[u8], device_id: &str, action: u8, timestamp_ms: u64) -> Vec<u8> {
    let mut out = Vec::with_capacity(AUTH_PREFIX.len() + challenge.len() + device_id.len() + 9);
    out.extend_from_slice(AUTH_PREFIX);
    out.extend_from_slice(challenge);
    out.extend_from_slice(device_id.as_bytes());
    out.push(action);
    out.extend_from_slice(&timestamp_ms.to_be_bytes());
    out
}

/// A rotating tag the phone puts in its Bluetooth advertisement. Only someone who
/// already knows the phone's device id can match it, so a passer-by sees a different
/// value every hour and cannot follow the phone around, while the Mac still knows
/// which of the Tandem phones nearby is its own.
pub fn ble_hint(id: &DeviceId, hour: u64) -> [u8; BLE_HINT_LEN] {
    let mut input = Vec::with_capacity(40);
    input.extend_from_slice(b"tandem-ble-hint-v1");
    input.extend_from_slice(id.as_bytes());
    input.extend_from_slice(&hour.to_be_bytes());
    let hash = digest::digest(&digest::SHA256, &input);
    let mut hint = [0u8; BLE_HINT_LEN];
    hint.copy_from_slice(&hash.as_ref()[..BLE_HINT_LEN]);
    hint
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_signed_bytes_are_stable() {
        // Known answer: the Kotlin and Swift sides call this same function, so this
        // pins the layout documented in docs/HOTSPOT.md.
        let message = auth_message(&[7u8; 16], "abcdefghijklmnopqrstuvwxyz", ACTION_ON, 0x0102_0304_0506_0708);
        let mut expected = b"tandem-hotspot-v1".to_vec();
        expected.extend_from_slice(&[7u8; 16]);
        expected.extend_from_slice(b"abcdefghijklmnopqrstuvwxyz");
        expected.push(1);
        expected.extend_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(message, expected);
    }

    #[test]
    fn every_field_changes_the_message() {
        let base = auth_message(&[1u8; 16], "id", ACTION_ON, 5);
        assert_ne!(base, auth_message(&[2u8; 16], "id", ACTION_ON, 5));
        assert_ne!(base, auth_message(&[1u8; 16], "other", ACTION_ON, 5));
        assert_ne!(base, auth_message(&[1u8; 16], "id", ACTION_OFF, 5));
        assert_ne!(base, auth_message(&[1u8; 16], "id", ACTION_ON, 6));
    }

    #[test]
    fn the_hint_rotates_and_is_per_device() {
        let a = DeviceId::from_bytes([1u8; 16]);
        let b = DeviceId::from_bytes([2u8; 16]);
        assert_eq!(ble_hint(&a, 100), ble_hint(&a, 100));
        assert_ne!(ble_hint(&a, 100), ble_hint(&a, 101));
        assert_ne!(ble_hint(&a, 100), ble_hint(&b, 100));
    }
}
