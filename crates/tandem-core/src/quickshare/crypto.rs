//! The handshake and the encryption under Quick Share: UKEY2 (an unauthenticated key exchange with a commitment), and the
//! "secure messages" that carry everything after it. Written from the reference code of google/ukey2 and google/securemessage.
//!
//! Nothing here proves who the other side is. In "Everyone" mode there is nobody to prove, and the person compares a four digit
//! PIN on both screens instead.

use aes::Aes256;
use cbc::cipher::{BlockModeDecrypt, BlockModeEncrypt, KeyIvInit, block_padding::Pkcs7};
use prost::Message;
use ring::{agreement, digest, hkdf, hmac, rand::{SecureRandom, SystemRandom}};

use super::proto::{
    securegcm::{DeviceToDeviceMessage, GcmMetadata, Type as GcmType, Ukey2ClientFinished, Ukey2ClientInit, Ukey2HandshakeCipher, Ukey2Message, Ukey2ServerInit, ukey2_client_init::CipherCommitment, ukey2_message},
    securemessage::{EcP256PublicKey, EncScheme, GenericPublicKey, Header, HeaderAndBodyInternal, PublicKeyType, SecureMessage, SigScheme},
};
use crate::{Error, Result};

pub const NEXT_PROTOCOL: &str = "AES_256_CBC-HMAC_SHA256";

/// SHA-256 of "D2D": the salt for the keys of the two directions.
const D2D_SALT: [u8; 32] = [
    0x82, 0xAA, 0x55, 0xA0, 0xD3, 0x97, 0xF8, 0x83, 0x46, 0xCA, 0x1C, 0xEE, 0x8D, 0x39, 0x09, 0xB9, 0x5F, 0x13, 0xFA, 0x7D, 0xEB, 0x1D,
    0x4A, 0xB3, 0x83, 0x76, 0xB8, 0x25, 0x6D, 0xA8, 0x55, 0x10,
];

fn bad(what: &str) -> Error {
    Error::protocol(format!("quick share: {what}"))
}

struct Len(usize);

impl hkdf::KeyType for Len {
    fn len(&self) -> usize {
        self.0
    }
}

fn hkdf32(ikm: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, salt).extract(ikm);
    let info = [info];
    let okm = prk.expand(&info, Len(32)).expect("32 bytes is a valid length");
    let mut out = [0u8; 32];
    okm.fill(&mut out).expect("the buffer is the length asked for");
    out
}

fn random<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    SystemRandom::new().fill(&mut out).expect("the system has randomness");
    out
}

// ---- Keys on the wire -------------------------------------------------------------------------------------------------------

/// A coordinate the way a Java BigInteger writes it: big-endian, no leading zeros, and a zero in front when the top bit is set.
fn encode_coordinate(c: &[u8]) -> Vec<u8> {
    let start = c.iter().position(|b| *b != 0).unwrap_or(c.len());
    let c = &c[start..];
    match c.first() {
        None => vec![0],
        Some(first) if first & 0x80 != 0 => [&[0u8][..], c].concat(),
        Some(_) => c.to_vec(),
    }
}

fn decode_coordinate(c: &[u8]) -> Result<[u8; 32]> {
    let start = c.iter().position(|b| *b != 0).unwrap_or(c.len());
    let c = &c[start..];
    if c.len() > 32 {
        return Err(bad("a coordinate of a key is too long"));
    }
    let mut out = [0u8; 32];
    out[32 - c.len()..].copy_from_slice(c);
    Ok(out)
}

/// The public key as the handshake sends it, from the uncompressed point (`04 || x || y`) of ring.
fn encode_public_key(uncompressed: &[u8]) -> Vec<u8> {
    let key = GenericPublicKey {
        r#type: PublicKeyType::EcP256 as i32,
        ec_p256_public_key: Some(EcP256PublicKey { x: encode_coordinate(&uncompressed[1..33]), y: encode_coordinate(&uncompressed[33..65]) }),
        rsa2048_public_key: None,
        dh2048_public_key: None,
    };
    key.encode_to_vec()
}

fn decode_public_key(bytes: &[u8]) -> Result<Vec<u8>> {
    let key = GenericPublicKey::decode(bytes).map_err(|_| bad("a public key does not parse"))?;
    if key.r#type != PublicKeyType::EcP256 as i32 {
        return Err(bad("a public key is not of the curve P-256"));
    }
    let point = key.ec_p256_public_key.ok_or_else(|| bad("a public key has no point"))?;
    let mut out = vec![4u8];
    out.extend_from_slice(&decode_coordinate(&point.x)?);
    out.extend_from_slice(&decode_coordinate(&point.y)?);
    Ok(out)
}

fn wrap(kind: ukey2_message::Type, data: Vec<u8>) -> Vec<u8> {
    Ukey2Message { message_type: Some(kind as i32), message_data: Some(data) }.encode_to_vec()
}

fn unwrap(bytes: &[u8], expected: ukey2_message::Type) -> Result<Vec<u8>> {
    let message = Ukey2Message::decode(bytes).map_err(|_| bad("a handshake message does not parse"))?;
    if message.message_type != Some(expected as i32) {
        return Err(bad("a handshake message came out of turn"));
    }
    message.message_data.ok_or_else(|| bad("a handshake message is empty"))
}

// ---- The handshake ----------------------------------------------------------------------------------------------------------

/// What both sides hold when the handshake is done.
pub struct Agreed {
    /// What the person compares on both screens, as four digits.
    pub auth_string: [u8; 32],
    pub next_secret: [u8; 32],
}

impl Agreed {
    /// The PIN of four digits both screens show. Worked out the way the Android code does it, from memory of that code: it has
    /// to be compared with a real phone.
    pub fn pin(&self) -> String {
        let (mut hash, mut multiplier) = (0i32, 1i32);
        for byte in self.auth_string {
            hash = (hash + (byte as i8 as i32) * multiplier) % 9973;
            multiplier = (multiplier * 31) % 9973;
        }
        format!("{:04}", hash.abs())
    }
}

fn agree(private: agreement::EphemeralPrivateKey, their_public: &[u8], client_init: &[u8], server_init: &[u8]) -> Result<Agreed> {
    let their = agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, their_public);
    let derived = agreement::agree_ephemeral(private, &their, |shared| digest::digest(&digest::SHA256, shared))
        .map_err(|_| bad("the keys could not be agreed on"))?;
    let derived = derived.as_ref();
    let mut log = client_init.to_vec();
    log.extend_from_slice(server_init);
    Ok(Agreed { auth_string: hkdf32(derived, b"UKEY2 v1 auth", &log), next_secret: hkdf32(derived, b"UKEY2 v1 next", &log) })
}

/// The side that connected.
pub struct Client {
    private: Option<agreement::EphemeralPrivateKey>,
    init: Vec<u8>,
    finish: Vec<u8>,
}

impl Client {
    /// The first message to send. The last one is made at the same time, because the first holds a commitment to it.
    pub fn start() -> Result<(Client, Vec<u8>)> {
        let rng = SystemRandom::new();
        let private = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng).map_err(|_| bad("no key could be made"))?;
        let public = private.compute_public_key().map_err(|_| bad("no public key could be made"))?;
        let finish = wrap(
            ukey2_message::Type::ClientFinish,
            Ukey2ClientFinished { public_key: Some(encode_public_key(public.as_ref())) }.encode_to_vec(),
        );
        let commitment = digest::digest(&digest::SHA512, &finish).as_ref().to_vec();
        let init = wrap(
            ukey2_message::Type::ClientInit,
            Ukey2ClientInit {
                version: Some(1),
                random: Some(random::<32>().to_vec()),
                cipher_commitments: vec![CipherCommitment { handshake_cipher: Some(Ukey2HandshakeCipher::P256Sha512 as i32), commitment: Some(commitment) }],
                next_protocol: Some(NEXT_PROTOCOL.to_string()),
            }
            .encode_to_vec(),
        );
        Ok((Client { private: Some(private), init: init.clone(), finish }, init))
    }

    /// The answer of the other side. Gives the last message to send, and what was agreed.
    pub fn finish(mut self, server_init: &[u8]) -> Result<(Vec<u8>, Agreed)> {
        let data = unwrap(server_init, ukey2_message::Type::ServerInit)?;
        let message = Ukey2ServerInit::decode(data.as_slice()).map_err(|_| bad("the answer of the server does not parse"))?;
        if message.version != Some(1) || message.handshake_cipher != Some(Ukey2HandshakeCipher::P256Sha512 as i32) {
            return Err(bad("the server wants a handshake that is not offered"));
        }
        if message.random.as_deref().map(<[u8]>::len) != Some(32) {
            return Err(bad("the answer of the server has no usable random"));
        }
        let theirs = decode_public_key(message.public_key.as_deref().unwrap_or_default())?;
        let private = self.private.take().ok_or_else(|| bad("the handshake was already used"))?;
        let agreed = agree(private, &theirs, &self.init, server_init)?;
        Ok((self.finish, agreed))
    }
}

/// The side that was connected to.
pub struct Server {
    private: Option<agreement::EphemeralPrivateKey>,
    init: Vec<u8>,
    server_init: Vec<u8>,
    commitment: Vec<u8>,
}

impl Server {
    /// The first message of the client, and the answer to send.
    pub fn start(client_init: &[u8]) -> Result<(Server, Vec<u8>)> {
        let data = unwrap(client_init, ukey2_message::Type::ClientInit)?;
        let message = Ukey2ClientInit::decode(data.as_slice()).map_err(|_| bad("the first message does not parse"))?;
        if message.version != Some(1) || message.random.as_deref().map(<[u8]>::len) != Some(32) {
            return Err(bad("the first message has a wrong version or random"));
        }
        if message.next_protocol.as_deref() != Some(NEXT_PROTOCOL) {
            return Err(bad("the client asks for a protocol that is not offered"));
        }
        let commitment = message
            .cipher_commitments
            .iter()
            .find(|c| c.handshake_cipher == Some(Ukey2HandshakeCipher::P256Sha512 as i32))
            .and_then(|c| c.commitment.clone())
            .ok_or_else(|| bad("the client offers no handshake that is known here"))?;
        let rng = SystemRandom::new();
        let private = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng).map_err(|_| bad("no key could be made"))?;
        let public = private.compute_public_key().map_err(|_| bad("no public key could be made"))?;
        let server_init = wrap(
            ukey2_message::Type::ServerInit,
            Ukey2ServerInit {
                version: Some(1),
                random: Some(random::<32>().to_vec()),
                handshake_cipher: Some(Ukey2HandshakeCipher::P256Sha512 as i32),
                public_key: Some(encode_public_key(public.as_ref())),
            }
            .encode_to_vec(),
        );
        Ok((Server { private: Some(private), init: client_init.to_vec(), server_init: server_init.clone(), commitment }, server_init))
    }

    /// The last message of the client: it has to be the one that was committed to.
    pub fn finish(mut self, client_finish: &[u8]) -> Result<Agreed> {
        if digest::digest(&digest::SHA512, client_finish).as_ref() != self.commitment.as_slice() {
            return Err(bad("the last message is not the one that was committed to"));
        }
        let data = unwrap(client_finish, ukey2_message::Type::ClientFinish)?;
        let message = Ukey2ClientFinished::decode(data.as_slice()).map_err(|_| bad("the last message does not parse"))?;
        let theirs = decode_public_key(message.public_key.as_deref().unwrap_or_default())?;
        let private = self.private.take().ok_or_else(|| bad("the handshake was already used"))?;
        agree(private, &theirs, &self.init, &self.server_init)
    }
}

// ---- The secure messages after it -------------------------------------------------------------------------------------------

/// What carries every message once the handshake is done: encrypted with AES-256-CBC, signed with HMAC-SHA-256, numbered per
/// direction. The numbers start at one.
pub struct Channel {
    encode_key: [u8; 32],
    encode_sign: hmac::Key,
    decode_key: [u8; 32],
    decode_sign: hmac::Key,
    sent: i32,
    received: i32,
}

type Encryptor = cbc::Encryptor<Aes256>;
type Decryptor = cbc::Decryptor<Aes256>;

fn secure_message_salt() -> [u8; 32] {
    let mut out = [0u8; 32];
    out.copy_from_slice(digest::digest(&digest::SHA256, b"SecureMessage").as_ref());
    out
}

impl Channel {
    pub fn new(next_secret: &[u8; 32], is_client: bool) -> Channel {
        let client = hkdf32(next_secret, &D2D_SALT, b"client");
        let server = hkdf32(next_secret, &D2D_SALT, b"server");
        let (encode, decode) = if is_client { (client, server) } else { (server, client) };
        let salt = secure_message_salt();
        let keys = |key: &[u8; 32]| (hkdf32(key, &salt, b"ENC:2"), hmac::Key::new(hmac::HMAC_SHA256, &hkdf32(key, &salt, b"SIG:1")));
        let (encode_key, encode_sign) = keys(&encode);
        let (decode_key, decode_sign) = keys(&decode);
        Channel { encode_key, encode_sign, decode_key, decode_sign, sent: 0, received: 0 }
    }

    /// Wraps one message for the other side.
    pub fn seal(&mut self, message: &[u8]) -> Vec<u8> {
        self.sent += 1;
        let plain = DeviceToDeviceMessage { message: Some(message.to_vec()), sequence_number: Some(self.sent) }.encode_to_vec();
        let iv = random::<16>();
        let body = Encryptor::new(&self.encode_key.into(), &iv.into()).encrypt_padded_vec::<Pkcs7>(&plain);
        let header = Header {
            signature_scheme: SigScheme::HmacSha256 as i32,
            encryption_scheme: EncScheme::Aes256Cbc as i32,
            verification_key_id: None,
            decryption_key_id: None,
            iv: Some(iv.to_vec()),
            public_metadata: Some(GcmMetadata { r#type: GcmType::DeviceToDeviceMessage as i32, version: Some(1) }.encode_to_vec()),
            associated_data_length: None,
        }
        .encode_to_vec();
        let header_and_body = HeaderAndBodyInternal { header, body }.encode_to_vec();
        let signature = hmac::sign(&self.encode_sign, &header_and_body).as_ref().to_vec();
        SecureMessage { header_and_body, signature }.encode_to_vec()
    }

    /// Checks and opens one message of the other side.
    pub fn open(&mut self, bytes: &[u8]) -> Result<Vec<u8>> {
        let message = SecureMessage::decode(bytes).map_err(|_| bad("a message does not parse"))?;
        hmac::verify(&self.decode_sign, &message.header_and_body, &message.signature).map_err(|_| bad("a message has a wrong signature"))?;
        let inner = HeaderAndBodyInternal::decode(message.header_and_body.as_slice()).map_err(|_| bad("a message has no header and body"))?;
        let header = Header::decode(inner.header.as_slice()).map_err(|_| bad("a message has no header"))?;
        if header.encryption_scheme != EncScheme::Aes256Cbc as i32 || header.signature_scheme != SigScheme::HmacSha256 as i32 {
            return Err(bad("a message uses a scheme that is not supported"));
        }
        let iv: [u8; 16] = header.iv.as_deref().and_then(|iv| iv.try_into().ok()).ok_or_else(|| bad("a message has no usable iv"))?;
        let plain = Decryptor::new(&self.decode_key.into(), &iv.into())
            .decrypt_padded_vec::<Pkcs7>(&inner.body)
            .map_err(|_| bad("a message does not decrypt"))?;
        let d2d = DeviceToDeviceMessage::decode(plain.as_slice()).map_err(|_| bad("a message holds no message"))?;
        self.received += 1;
        if d2d.sequence_number != Some(self.received) {
            return Err(bad("a message is out of order"));
        }
        d2d.message.ok_or_else(|| bad("a message is empty"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_salt_of_the_two_directions_is_sha256_of_d2d() {
        assert_eq!(digest::digest(&digest::SHA256, b"D2D").as_ref(), D2D_SALT);
    }

    #[test]
    fn coordinates_are_written_like_a_biginteger_and_read_back() {
        assert_eq!(encode_coordinate(&[0, 0, 0x80, 1]), vec![0, 0x80, 1]);
        assert_eq!(encode_coordinate(&[0, 0, 0x7f, 1]), vec![0x7f, 1]);
        assert_eq!(encode_coordinate(&[0, 0]), vec![0]);
        let mut full = [0u8; 32];
        full[31] = 7;
        assert_eq!(decode_coordinate(&encode_coordinate(&full)).unwrap(), full);
        assert!(decode_coordinate(&[1u8; 33]).is_err());
    }

    fn pair() -> (Channel, Channel, Agreed, Agreed) {
        let (client, init) = Client::start().unwrap();
        let (server, server_init) = Server::start(&init).unwrap();
        let (finish, client_agreed) = client.finish(&server_init).unwrap();
        let server_agreed = server.finish(&finish).unwrap();
        let a = Channel::new(&client_agreed.next_secret, true);
        let b = Channel::new(&server_agreed.next_secret, false);
        (a, b, client_agreed, server_agreed)
    }

    #[test]
    fn both_sides_agree_on_the_secret_and_the_pin() {
        let (_, _, client, server) = pair();
        assert_eq!(client.auth_string, server.auth_string);
        assert_eq!(client.next_secret, server.next_secret);
        assert_eq!(client.pin(), server.pin());
        assert_eq!(client.pin().len(), 4);
    }

    #[test]
    fn messages_go_both_ways_in_order_and_nothing_else_gets_through() {
        let (mut a, mut b, _, _) = pair();
        let one = a.seal(b"hello");
        let two = a.seal(b"again");
        assert_eq!(b.open(&one).unwrap(), b"hello");
        assert_eq!(b.open(&two).unwrap(), b"again");
        let back = b.seal(b"and back");
        assert_eq!(a.open(&back).unwrap(), b"and back");
        // A message cannot be played twice, nor out of turn, nor changed.
        assert!(b.open(&one).is_err());
        let mut third = a.seal(b"third");
        let last = third.len() - 1;
        third[last] ^= 1;
        assert!(b.open(&third).is_err());
    }

    #[test]
    fn a_last_message_that_was_not_committed_to_is_refused() {
        let (client, init) = Client::start().unwrap();
        let (server, server_init) = Server::start(&init).unwrap();
        let (_finish, _) = client.finish(&server_init).unwrap();
        let (other, _) = Client::start().unwrap();
        let _ = other;
        assert!(server.finish(&wrap(ukey2_message::Type::ClientFinish, vec![1, 2, 3])).is_err());
    }
}
