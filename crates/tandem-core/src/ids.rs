//! Device identifiers and the small value types every module shares.

use std::fmt;

use data_encoding::{BASE32_NOPAD, BASE64URL_NOPAD};
use ring::digest;
use serde::de::{self, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::error::{Error, Result};

pub const ID_LEN: usize = 16;

/// Who a device is: the first 128 bits of the SHA-256 of its Ed25519 public key.
///
/// The id is derived from the key rather than chosen, so nobody can claim an id
/// without holding the matching private key.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct DeviceId([u8; ID_LEN]);

impl DeviceId {
    pub fn from_public_key(public: &[u8; 32]) -> Self {
        let hash = digest::digest(&digest::SHA256, public);
        let mut id = [0u8; ID_LEN];
        id.copy_from_slice(&hash.as_ref()[..ID_LEN]);
        DeviceId(id)
    }

    pub fn from_bytes(bytes: [u8; ID_LEN]) -> Self {
        DeviceId(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; ID_LEN] {
        &self.0
    }

    pub fn parse(text: &str) -> Result<Self> {
        let raw = BASE32_NOPAD
            .decode(text.trim().to_ascii_uppercase().as_bytes())
            .map_err(|_| Error::invalid("device id is not valid base32"))?;
        let bytes: [u8; ID_LEN] = raw
            .try_into()
            .map_err(|_| Error::invalid("device id has the wrong length"))?;
        Ok(DeviceId(bytes))
    }

    /// Five characters, enough for a person to tell two devices apart.
    pub fn short(&self) -> String {
        self.to_string()[..5].to_string()
    }
}

impl fmt::Display for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&BASE32_NOPAD.encode(&self.0).to_ascii_lowercase())
    }
}

impl fmt::Debug for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DeviceId({}..)", self.short())
    }
}

impl Serialize for DeviceId {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_bytes(&self.0)
    }
}

impl<'de> Deserialize<'de> for DeviceId {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        bytes_array::deserialize::<D, ID_LEN>(deserializer).map(DeviceId)
    }
}

/// Serde helper that writes a fixed-size array as a byte string instead of a list of
/// integers. CBOR then stores 32 bytes as 34, not as 32 separate numbers.
pub mod bytes_array {
    use super::*;

    pub fn serialize<S: Serializer, const N: usize>(
        value: &[u8; N],
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_bytes(value)
    }

    pub fn deserialize<'de, D: Deserializer<'de>, const N: usize>(
        deserializer: D,
    ) -> std::result::Result<[u8; N], D::Error> {
        struct ArrayVisitor<const N: usize>;

        impl<'de, const N: usize> Visitor<'de> for ArrayVisitor<N> {
            type Value = [u8; N];

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "a byte string of length {N}")
            }

            fn visit_bytes<E: de::Error>(self, v: &[u8]) -> std::result::Result<Self::Value, E> {
                v.try_into().map_err(|_| E::invalid_length(v.len(), &self))
            }

            fn visit_byte_buf<E: de::Error>(self, v: Vec<u8>) -> std::result::Result<Self::Value, E> {
                self.visit_bytes(&v)
            }

            fn visit_seq<A: de::SeqAccess<'de>>(
                self,
                mut seq: A,
            ) -> std::result::Result<Self::Value, A::Error> {
                let mut out = [0u8; N];
                for (index, slot) in out.iter_mut().enumerate() {
                    *slot = seq
                        .next_element()?
                        .ok_or_else(|| de::Error::invalid_length(index, &self))?;
                }
                Ok(out)
            }
        }

        deserializer.deserialize_bytes(ArrayVisitor::<N>)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Platform {
    Android,
    MacOs,
    Linux,
    Windows,
    Ios,
    #[serde(other)]
    Other,
}

impl Platform {
    pub fn current() -> Self {
        if cfg!(target_os = "android") {
            Platform::Android
        } else if cfg!(target_os = "macos") {
            Platform::MacOs
        } else if cfg!(target_os = "linux") {
            Platform::Linux
        } else if cfg!(target_os = "windows") {
            Platform::Windows
        } else if cfg!(target_os = "ios") {
            Platform::Ios
        } else {
            Platform::Other
        }
    }
}

pub fn b64_encode(bytes: &[u8]) -> String {
    BASE64URL_NOPAD.encode(bytes)
}

pub fn b64_decode(text: &str) -> Result<Vec<u8>> {
    BASE64URL_NOPAD
        .decode(text.as_bytes())
        .map_err(|_| Error::invalid("not valid base64url"))
}

/// Milliseconds since the Unix epoch.
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn id_round_trips_through_text() {
        let id = DeviceId::from_public_key(&[7u8; 32]);
        let text = id.to_string();
        assert_eq!(text.len(), 26);
        assert_eq!(DeviceId::parse(&text).unwrap(), id);
        assert_eq!(DeviceId::parse(&text.to_uppercase()).unwrap(), id);
    }

    #[test]
    fn id_round_trips_through_cbor() {
        let id = DeviceId::from_public_key(&[9u8; 32]);
        let mut buf = Vec::new();
        ciborium::into_writer(&id, &mut buf).unwrap();
        // One byte for the header, sixteen for the payload.
        assert_eq!(buf.len(), 17);
        let back: DeviceId = ciborium::from_reader(buf.as_slice()).unwrap();
        assert_eq!(back, id);
    }

    #[test]
    fn unknown_platform_falls_back_to_other() {
        let mut buf = Vec::new();
        ciborium::into_writer("haiku", &mut buf).unwrap();
        let platform: Platform = ciborium::from_reader(buf.as_slice()).unwrap();
        assert_eq!(platform, Platform::Other);
    }
}
