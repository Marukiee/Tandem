pub mod ble;
pub mod circle;
pub mod discovery;
pub mod engine;
pub mod error;
pub mod events;
pub mod files;
pub mod ffi;
pub mod hotspot;
pub mod identity;
pub mod ids;
pub mod live;
pub mod media;
pub mod net;
pub mod otp;
pub mod pairing;
pub mod platform;
pub mod layout;
pub mod pointer_share;
pub mod quickshare;
pub mod proto;
pub mod session;
pub mod store;
pub mod tls;
pub mod transfer;
#[cfg(feature = "webdav")]
pub mod webdav;

pub use engine::{DeviceInfo, Engine, EngineConfig};
pub use error::{Error, Result};
pub use events::Event;
pub use ids::{DeviceId, Platform};

uniffi::setup_scaffolding!("tandem_core");
