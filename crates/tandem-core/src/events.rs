//! What the core tells the app. The app subscribes once and reacts.

use crate::ids::DeviceId;
use crate::proto::{
    CallAction, CallMsg, CaptureKind, CaptureWhy, HotspotMsg, InputMsg, MediaAction, MediaPlayer, NotificationAction, NotificationMsg, ShareOffer,
};

#[derive(Clone, Debug)]
pub struct TransferProgress {
    pub offer: u64,
    pub index: u32,
    pub peer: DeviceId,
    pub incoming: bool,
    pub name: String,
    pub done: u64,
    pub total: u64,
}

#[derive(Clone, Debug)]
pub struct TransferDone {
    pub offer: u64,
    pub index: u32,
    pub peer: DeviceId,
    pub incoming: bool,
    pub name: String,
    pub size: u64,
    /// Where an incoming file ended up. `None` for outgoing files and failures.
    pub location: Option<String>,
    pub error: Option<String>,
}

#[derive(Clone, Debug)]
pub enum Event {
    /// Devices, their online state or their status changed. Ask for the list again.
    DevicesChanged,
    Connected { id: DeviceId },
    Disconnected { id: DeviceId },
    /// A device joined the circle through pairing on this device.
    Paired { id: DeviceId },
    CircleChanged,
    /// Another device removed this one. It can no longer connect until it starts over
    /// with a new identity.
    RemovedFromCircle,

    Clipboard { from: DeviceId, text: String, is_url: bool },
    ShareOffered { from: DeviceId, offer: ShareOffer },
    ShareText { from: DeviceId, text: String, is_url: bool, open: bool },
    Progress(TransferProgress),
    Finished(TransferDone),

    Notification { from: DeviceId, notification: NotificationMsg },
    NotificationRemoved { from: DeviceId, key: String },
    NotificationAction { from: DeviceId, action: NotificationAction },
    AppIcon { from: DeviceId, app_id: String, png: Vec<u8> },
    Call { from: DeviceId, call: CallMsg },
    CallAction { from: DeviceId, action: CallAction },
    Dial { from: DeviceId, number: String },
    Ring { from: DeviceId, on: bool },
    Input { from: DeviceId, input: InputMsg },
    Hotspot { from: DeviceId, hotspot: HotspotMsg },
    /// The other device wants a picture. Open the camera, then answer with a file share whose origin is
    /// `ShareOrigin::Capture(id)`, or with `cancel_capture`.
    CaptureRequested { from: DeviceId, id: u64, kind: CaptureKind },
    CaptureCancelled { from: DeviceId, id: u64, why: CaptureWhy },
    MediaPlayers { from: DeviceId, players: Vec<MediaPlayer> },
    MediaArt { from: DeviceId, key: u64, jpeg: Vec<u8> },
    MediaCommand { from: DeviceId, player: String, action: MediaAction, position_ms: Option<u64> },
    AudioStart { from: DeviceId, stream: u8, sample_rate: u32, channels: u8 },
    AudioStop { from: DeviceId, stream: u8 },

    /// Another device asks for the screen or the camera of this one. Same news as `MediaHost::on_request`; the
    /// callback is the one to act on, this is for whatever else wants to know.
    MediaRequested { from: DeviceId, request: crate::live::MediaRequest, pre_approved: bool },
    MediaStarted { peer: DeviceId, session: u64, kind: crate::live::MediaKind, role: crate::live::MediaRole },
    MediaEnded {
        peer: DeviceId,
        session: u64,
        kind: crate::live::MediaKind,
        role: crate::live::MediaRole,
        reason: crate::live::MediaEnd,
    },
    /// The bitrate the encoder of a host session should move to.
    MediaBitrate { peer: DeviceId, session: u64, bits_per_second: u32 },
    /// The other device is ready to show its screen or camera and asks this one to look (`Engine::media_offer`). Answer
    /// with `media_request` if the person wants to see it.
    MediaOffered { from: DeviceId, kind: crate::live::MediaKind, facing: crate::live::MediaFacing },
    /// The pointer goes over or comes back (see `pointer_share`).
    PointerShare { from: DeviceId, msg: crate::pointer_share::PointerShareMsg },
}
