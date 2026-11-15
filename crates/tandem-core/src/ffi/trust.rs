//! What each device may do here, for the apps (P6, see `crate::trust`). The core checks it; an app only shows and changes it.

use crate::ids::DeviceId;
use crate::trust::{Permission, TrustEdge, TrustLevel};

use super::{TandemEngine, TandemError};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum TandemPermission {
    /// Same switch as `TandemDevice::auto_accept`.
    FilesWithoutAsking,
    /// Same switch as `TandemDevice::clipboard_enabled`.
    Clipboard,
    Messages,
    /// Same switch as `TandemFilePolicy::enabled`.
    BrowseFiles,
    /// Same switch as `TandemFilePolicy::delete`.
    DeleteFiles,
    SeeNotifications,
    Control,
    Unlock,
    Lock,
    WifiPassword,
    Calls,
    Restart,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemTrustLevel {
    Own,
    Limited,
    SendOnly,
    Custom,
}

/// What `from` lets `to` do. `denied` is None when `from` is of a version without permissions, so nobody knows.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemTrustEdge {
    pub from: String,
    pub to: String,
    pub denied: Option<Vec<TandemPermission>>,
}

macro_rules! both_ways {
    ($($name:ident),*) => {
        impl From<Permission> for TandemPermission {
            fn from(p: Permission) -> Self {
                match p { $(Permission::$name => TandemPermission::$name),* }
            }
        }
        impl From<TandemPermission> for Permission {
            fn from(p: TandemPermission) -> Self {
                match p { $(TandemPermission::$name => Permission::$name),* }
            }
        }
    };
}

both_ways!(FilesWithoutAsking, Clipboard, Messages, BrowseFiles, DeleteFiles, SeeNotifications, Control, Unlock, Lock, WifiPassword, Calls, Restart);

impl From<TrustLevel> for TandemTrustLevel {
    fn from(level: TrustLevel) -> Self {
        match level {
            TrustLevel::Own => TandemTrustLevel::Own,
            TrustLevel::Limited => TandemTrustLevel::Limited,
            TrustLevel::SendOnly => TandemTrustLevel::SendOnly,
            TrustLevel::Custom => TandemTrustLevel::Custom,
        }
    }
}

impl From<TandemTrustLevel> for TrustLevel {
    fn from(level: TandemTrustLevel) -> Self {
        match level {
            TandemTrustLevel::Own => TrustLevel::Own,
            TandemTrustLevel::Limited => TrustLevel::Limited,
            TandemTrustLevel::SendOnly => TrustLevel::SendOnly,
            TandemTrustLevel::Custom => TrustLevel::Custom,
        }
    }
}

impl From<TrustEdge> for TandemTrustEdge {
    fn from(edge: TrustEdge) -> Self {
        TandemTrustEdge {
            from: edge.from.to_string(),
            to: edge.to.to_string(),
            denied: edge.denied.map(|list| list.into_iter().map(Into::into).collect()),
        }
    }
}

#[uniffi::export]
impl TandemEngine {
    /// What device `id` may not do on this one. Empty, the default, is everything.
    pub fn denied_permissions(&self, id: String) -> Result<Vec<TandemPermission>, TandemError> {
        Ok(self.engine.denied_permissions(&DeviceId::parse(&id)?).into_iter().map(Into::into).collect())
    }

    pub fn set_permission(&self, id: String, permission: TandemPermission, allowed: bool) -> Result<(), TandemError> {
        self.engine.set_permission(&DeviceId::parse(&id)?, permission.into(), allowed).map_err(Into::into)
    }

    pub fn trust_level(&self, id: String) -> Result<TandemTrustLevel, TandemError> {
        Ok(self.engine.trust_level(&DeviceId::parse(&id)?).into())
    }

    /// Sets every permission of device `id` to those of `level`. `Custom` changes nothing.
    pub fn set_trust_level(&self, id: String, level: TandemTrustLevel) -> Result<(), TandemError> {
        self.engine.set_trust_level(&DeviceId::parse(&id)?, level.into()).map_err(Into::into)
    }

    /// Who lets whom do what across the circle, for drawing it: a line for each pair of devices, both ways.
    pub fn trust_web(&self) -> Vec<TandemTrustEdge> {
        self.engine.trust_web().into_iter().map(Into::into).collect()
    }
}
