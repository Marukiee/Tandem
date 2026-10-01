pub mod ble;
pub mod circle;
pub mod discovery;
pub mod engine;
pub mod error;
pub mod events;
pub mod ffi;
pub mod hotspot;
pub mod identity;
pub mod ids;
pub mod media;
pub mod net;
pub mod pairing;
pub mod platform;
pub mod proto;
pub mod session;
pub mod store;
pub mod tls;
pub mod transfer;

pub use engine::{DeviceInfo, Engine, EngineConfig};
pub use error::{Error, Result};
pub use events::Event;
pub use ids::{DeviceId, Platform};

uniffi::setup_scaffolding!("tandem_core");
