//! A device's long-term identity: one Ed25519 key that signs circle statements and
//! backs the self-signed certificate used for TLS. Peers pin the key, not the
//! certificate, so the certificate can be regenerated at will.

use rcgen::{CertificateParams, DnType, KeyPair, PKCS_ED25519, date_time_ymd};
use ring::rand::SystemRandom;
use ring::signature::{ED25519, Ed25519KeyPair, KeyPair as _, UnparsedPublicKey};
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};

use crate::error::{Error, Result};
use crate::ids::DeviceId;

pub struct Identity {
    pkcs8: Vec<u8>,
    key: Ed25519KeyPair,
    public: [u8; 32],
    id: DeviceId,
    cert: CertificateDer<'static>,
}

impl Identity {
    pub fn generate() -> Result<Self> {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new())
            .map_err(|_| Error::crypto("could not generate a key"))?;
        Self::from_pkcs8(pkcs8.as_ref())
    }

    pub fn from_pkcs8(pkcs8: &[u8]) -> Result<Self> {
        let key = Ed25519KeyPair::from_pkcs8(pkcs8)
            .map_err(|_| Error::crypto("stored identity is not a valid Ed25519 key"))?;
        let public: [u8; 32] = key
            .public_key()
            .as_ref()
            .try_into()
            .map_err(|_| Error::crypto("unexpected public key length"))?;
        let id = DeviceId::from_public_key(&public);
        let cert = self_signed_certificate(pkcs8, &id)?;
        Ok(Identity {
            pkcs8: pkcs8.to_vec(),
            key,
            public,
            id,
            cert,
        })
    }

    pub fn id(&self) -> DeviceId {
        self.id
    }

    pub fn public_key(&self) -> [u8; 32] {
        self.public
    }

    /// The secret half, for the platform to keep in its secure store.
    pub fn to_pkcs8(&self) -> &[u8] {
        &self.pkcs8
    }

    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        let mut out = [0u8; 64];
        out.copy_from_slice(self.key.sign(message).as_ref());
        out
    }

    pub fn certificate(&self) -> CertificateDer<'static> {
        self.cert.clone()
    }

    pub fn private_key_der(&self) -> PrivatePkcs8KeyDer<'static> {
        PrivatePkcs8KeyDer::from(self.pkcs8.clone())
    }
}

impl std::fmt::Debug for Identity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Identity").field("id", &self.id).finish_non_exhaustive()
    }
}

pub fn verify_signature(public: &[u8; 32], message: &[u8], signature: &[u8]) -> bool {
    UnparsedPublicKey::new(&ED25519, public)
        .verify(message, signature)
        .is_ok()
}

fn self_signed_certificate(pkcs8: &[u8], id: &DeviceId) -> Result<CertificateDer<'static>> {
    let der = PrivatePkcs8KeyDer::from(pkcs8.to_vec());
    let key_pair = KeyPair::from_pkcs8_der_and_sign_algo(&der, &PKCS_ED25519)
        .map_err(|e| Error::crypto(format!("certificate key: {e}")))?;
    let mut params = CertificateParams::new(Vec::<String>::new())
        .map_err(|e| Error::crypto(format!("certificate params: {e}")))?;
    params
        .distinguished_name
        .push(DnType::CommonName, format!("tandem-{id}"));
    // Trust comes from pinning the key, so expiry would only ever cause outages.
    params.not_before = date_time_ymd(2024, 1, 1);
    params.not_after = date_time_ymd(2124, 1, 1);
    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| Error::crypto(format!("certificate: {e}")))?;
    Ok(cert.der().clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signs_and_verifies() {
        let identity = Identity::generate().unwrap();
        let sig = identity.sign(b"hello");
        assert!(verify_signature(&identity.public_key(), b"hello", &sig));
        assert!(!verify_signature(&identity.public_key(), b"hullo", &sig));
    }

    #[test]
    fn identity_survives_a_reload() {
        let first = Identity::generate().unwrap();
        let second = Identity::from_pkcs8(first.to_pkcs8()).unwrap();
        assert_eq!(first.id(), second.id());
        assert_eq!(first.public_key(), second.public_key());
    }

    #[test]
    fn certificate_carries_the_same_key() {
        let identity = Identity::generate().unwrap();
        let cert = identity.certificate();
        let from_cert = crate::tls::ed25519_key_of(&cert).unwrap();
        assert_eq!(from_cert, identity.public_key());
    }
}
