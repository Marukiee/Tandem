//! TLS 1.3 over QUIC, with trust decided by pinned Ed25519 keys instead of
//! certificate authorities.
//!
//! Both sides present a self-signed certificate and both check the other one.
//! The dialing side pins the exact key it expects, so a wrong server aborts the
//! handshake before the client certificate is ever sent. The accepting side
//! accepts any well-formed Ed25519 key at the TLS layer, because it cannot know
//! who is calling until the handshake completes, and then decides straight
//! away whether that key belongs to the circle. Nothing is processed before
//! that decision.

use std::sync::Arc;
use std::time::Duration;

use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, verify_tls13_signature};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, DistinguishedName, Error as TlsError, SignatureScheme};

use crate::error::{Error, Result};
use crate::identity::Identity;

pub const ALPN_MAIN: &[u8] = b"tandem/1";
pub const ALPN_PAIR: &[u8] = b"tandem-pair/1";

/// The name we present in the handshake. It carries no meaning: trust is the key.
pub const SERVER_NAME: &str = "tandem";

/// How aggressively a connection stays alive and how much data may be in flight.
#[derive(Clone, Copy, Debug)]
pub struct Tuning {
    pub keep_alive: Duration,
    pub idle_timeout: Duration,
}

impl Default for Tuning {
    fn default() -> Self {
        Tuning {
            keep_alive: Duration::from_secs(15),
            idle_timeout: Duration::from_secs(45),
        }
    }
}

/// Reads the raw Ed25519 public key out of a certificate, or `None` for any other
/// kind of key.
pub fn ed25519_key_of(cert: &CertificateDer<'_>) -> Option<[u8; 32]> {
    let (_, parsed) = x509_parser::parse_x509_certificate(cert.as_ref()).ok()?;
    let spki = parsed.public_key();
    if spki.algorithm.algorithm != x509_parser::oid_registry::OID_SIG_ED25519 {
        return None;
    }
    spki.subject_public_key.data.as_ref().try_into().ok()
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn transport(tuning: Tuning) -> Arc<quinn::TransportConfig> {
    let mut config = quinn::TransportConfig::default();
    config.max_concurrent_bidi_streams(128u32.into());
    config.max_concurrent_uni_streams(32u32.into());
    config.keep_alive_interval(Some(tuning.keep_alive));
    config.max_idle_timeout(quinn::IdleTimeout::try_from(tuning.idle_timeout).ok());
    // Large windows so a single file stream can fill a fast Wi-Fi link.
    config.stream_receive_window((16u32 * 1024 * 1024).into());
    config.receive_window((48u32 * 1024 * 1024).into());
    config.send_window(48 * 1024 * 1024);
    config.datagram_receive_buffer_size(Some(1 << 20));
    config.datagram_send_buffer_size(1 << 20);
    Arc::new(config)
}

pub fn server_config(identity: &Identity, tuning: Tuning) -> Result<quinn::ServerConfig> {
    let provider = provider();
    let mut crypto = rustls::ServerConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| Error::crypto(e.to_string()))?
        .with_client_cert_verifier(Arc::new(AnyKeyClientVerifier { provider }))
        .with_single_cert(
            vec![identity.certificate()],
            PrivateKeyDer::Pkcs8(identity.private_key_der()),
        )
        .map_err(|e| Error::crypto(e.to_string()))?;
    crypto.alpn_protocols = vec![ALPN_MAIN.to_vec(), ALPN_PAIR.to_vec()];
    crypto.max_early_data_size = 0;

    let quic = QuicServerConfig::try_from(crypto).map_err(|e| Error::crypto(e.to_string()))?;
    let mut config = quinn::ServerConfig::with_crypto(Arc::new(quic));
    config.transport_config(transport(tuning));
    Ok(config)
}

/// A client configuration for one outgoing connection. `expected` pins the
/// server's key; `None` accepts any key and is only for the pairing exchange,
/// which authenticates itself in another way.
pub fn client_config(
    identity: &Identity,
    expected: Option<[u8; 32]>,
    alpn: &[u8],
    tuning: Tuning,
) -> Result<quinn::ClientConfig> {
    let provider = provider();
    let mut crypto = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| Error::crypto(e.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedServerVerifier { expected, provider }))
        .with_client_auth_cert(
            vec![identity.certificate()],
            PrivateKeyDer::Pkcs8(identity.private_key_der()),
        )
        .map_err(|e| Error::crypto(e.to_string()))?;
    crypto.alpn_protocols = vec![alpn.to_vec()];
    crypto.enable_early_data = false;

    let quic = QuicClientConfig::try_from(crypto).map_err(|e| Error::crypto(e.to_string()))?;
    let mut config = quinn::ClientConfig::new(Arc::new(quic));
    config.transport_config(transport(tuning));
    Ok(config)
}

/// The key the other end proved it holds during the handshake.
pub fn peer_key(connection: &quinn::Connection) -> Option<[u8; 32]> {
    let identity = connection.peer_identity()?;
    let chain = identity.downcast::<Vec<CertificateDer<'static>>>().ok()?;
    ed25519_key_of(chain.first()?)
}

/// Which protocol was agreed, so the acceptor can tell pairing from normal traffic.
pub fn negotiated_alpn(connection: &quinn::Connection) -> Option<Vec<u8>> {
    let data = connection.handshake_data()?;
    let data = data.downcast::<quinn::crypto::rustls::HandshakeData>().ok()?;
    data.protocol.clone()
}

fn supported_schemes() -> Vec<SignatureScheme> {
    vec![SignatureScheme::ED25519]
}

#[derive(Debug)]
struct AnyKeyClientVerifier {
    provider: Arc<CryptoProvider>,
}

impl ClientCertVerifier for AnyKeyClientVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> std::result::Result<ClientCertVerified, TlsError> {
        ed25519_key_of(end_entity)
            .map(|_| ClientCertVerified::assertion())
            .ok_or_else(|| TlsError::General("client key is not Ed25519".into()))
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
        Err(TlsError::General("TLS 1.2 is not supported".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        supported_schemes()
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

#[derive(Debug)]
struct PinnedServerVerifier {
    expected: Option<[u8; 32]>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, TlsError> {
        let presented = ed25519_key_of(end_entity)
            .ok_or_else(|| TlsError::General("server key is not Ed25519".into()))?;
        match self.expected {
            Some(expected) if expected != presented => {
                Err(TlsError::General("server key does not match the paired device".into()))
            }
            _ => Ok(ServerCertVerified::assertion()),
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
        Err(TlsError::General("TLS 1.2 is not supported".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        supported_schemes()
    }
}
