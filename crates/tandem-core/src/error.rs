use thiserror::Error;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, Error)]
pub enum Error {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error("crypto error: {0}")]
    Crypto(String),
    #[error("that device is not part of your circle")]
    NotTrusted,
    #[error("pairing failed: {0}")]
    Pairing(String),
    #[error("connection error: {0}")]
    Connection(String),
    #[error("not connected to that device")]
    NotConnected,
    #[error("transfer failed: {0}")]
    Transfer(String),
    #[error("invalid input: {0}")]
    Invalid(String),
    #[error("cancelled")]
    Cancelled,
    /// The other device would not do what was asked with its files, and says why.
    #[error("{message}")]
    Files { code: crate::files::FsCode, message: String },
}

impl Error {
    pub fn protocol(msg: impl Into<String>) -> Self {
        Error::Protocol(msg.into())
    }

    pub fn crypto(msg: impl Into<String>) -> Self {
        Error::Crypto(msg.into())
    }

    pub fn connection(err: impl std::fmt::Display) -> Self {
        Error::Connection(err.to_string())
    }

    pub fn transfer(err: impl std::fmt::Display) -> Self {
        Error::Transfer(err.to_string())
    }

    pub fn invalid(msg: impl Into<String>) -> Self {
        Error::Invalid(msg.into())
    }
}
