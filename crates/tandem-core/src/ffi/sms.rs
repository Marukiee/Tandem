//! Messages for the apps (see `crate::sms`). A computer asks and gets the answer back from the same call; the phone hears
//! the question as `TandemEvent::Sms` and answers with one of the `sms_answer_` calls.

use crate::ids::DeviceId;
use crate::sms::{PhoneContact, PhoneNumber, SmsContact, SmsConversation, SmsMessage, SmsMsg, SmsRefusal, SmsStatus};

use super::{TandemEngine, TandemError, join_error};

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemSmsConversation {
    pub thread: u64,
    pub addresses: Vec<String>,
    /// What the contacts of the phone call them, empty when none of them is known.
    pub name: String,
    pub snippet: String,
    pub date: u64,
    pub unread: u32,
    pub from_me: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSmsStatus {
    Received,
    Sending,
    Sent,
    Failed,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemSmsMessage {
    /// With `mms`, the message on the phone.
    pub id: u64,
    pub mms: bool,
    pub thread: u64,
    /// Who sent it, for one that came in.
    pub address: String,
    pub body: String,
    pub date: u64,
    pub status: TandemSmsStatus,
    pub read: bool,
    /// The parts of an MMS that are pictures, for `sms_picture`.
    pub pictures: Vec<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemSmsContact {
    pub name: String,
    pub number: String,
    pub label: String,
}

/// One number of a person in the whole list of contacts of a phone.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemPhoneNumber {
    pub number: String,
    /// In the language of the phone, only for showing.
    pub label: String,
    /// The one to call without asking (the default number, or the only mobile one). At most one per person.
    pub preferred: bool,
}

/// A person in the contacts of a phone, with all their numbers. `id` stays the same while they are in the contacts.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemPhoneContact {
    pub id: u64,
    pub name: String,
    pub numbers: Vec<TandemPhoneNumber>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemSmsConversationPage {
    pub items: Vec<TandemSmsConversation>,
    pub more: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemSmsMessagePage {
    pub thread: u64,
    pub items: Vec<TandemSmsMessage>,
    pub more: bool,
}

/// What a computer asks of the phone.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSmsRequest {
    /// The conversations at or before `before` (milliseconds, 0 for the newest), newest first, at most `limit`.
    ListConversations { before: u64, limit: u32 },
    /// The messages of a conversation at or before `before`, newest first.
    ListMessages { thread: u64, before: u64, limit: u32 },
    Send { to: Vec<String>, text: String },
    FindContacts { query: String },
    /// One picture of an MMS, as a small JPEG.
    Picture { part: u64 },
    /// The people with a number whose contact id is above `after`, by that id, at most `limit`. Answer with `sms_answer_contact_page`.
    ListContacts { after: u64, limit: u32 },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSmsEvent {
    /// A computer asks the phone. Answer with the `sms_answer_` call that fits and the same `req`, or with `sms_refuse`.
    Request { req: u64, request: TandemSmsRequest },
    /// A computer asks the person on the phone to let it read and send messages: its switch, or the permission of Android.
    AskAccess,
    /// Messages that are new or changed on the phone, and the conversations they are in as they are now.
    Changed { conversations: Vec<TandemSmsConversation>, messages: Vec<TandemSmsMessage> },
    /// An answer, taken by the call that asked. Nothing to do.
    Answered,
}

/// Why the phone does not answer a question of a computer.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSmsRefusal {
    /// Messages is off for that computer here.
    Off,
    /// Android has not given Tandem the permission to read or send messages.
    NoPermission,
    Unavailable,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TandemSmsError {
    /// Messages is switched off for this device on the phone.
    #[error("messages are off for this device on the phone")]
    Off,
    #[error("the phone has not allowed Tandem to read its messages")]
    NoPermission,
    #[error("that device has no messages")]
    Unavailable,
    #[error("not connected to that device")]
    NotConnected,
    #[error("{reason}")]
    Failed { reason: String },
}

impl From<SmsConversation> for TandemSmsConversation {
    fn from(c: SmsConversation) -> Self {
        TandemSmsConversation {
            thread: c.thread,
            addresses: c.addresses,
            name: c.name,
            snippet: c.snippet,
            date: c.date,
            unread: c.unread,
            from_me: c.from_me,
        }
    }
}

impl From<TandemSmsConversation> for SmsConversation {
    fn from(c: TandemSmsConversation) -> Self {
        SmsConversation {
            thread: c.thread,
            addresses: c.addresses,
            name: c.name,
            snippet: c.snippet,
            date: c.date,
            unread: c.unread,
            from_me: c.from_me,
        }
    }
}

impl From<SmsMessage> for TandemSmsMessage {
    fn from(m: SmsMessage) -> Self {
        let status = match m.status {
            SmsStatus::Received => TandemSmsStatus::Received,
            SmsStatus::Sending => TandemSmsStatus::Sending,
            SmsStatus::Failed => TandemSmsStatus::Failed,
            // A state a newer phone knows of is one of a message on its way or gone out.
            SmsStatus::Sent | SmsStatus::Other => TandemSmsStatus::Sent,
        };
        TandemSmsMessage {
            id: m.id,
            mms: m.mms,
            thread: m.thread,
            address: m.address,
            body: m.body,
            date: m.date,
            status,
            read: m.read,
            pictures: m.pictures,
        }
    }
}

impl From<TandemSmsMessage> for SmsMessage {
    fn from(m: TandemSmsMessage) -> Self {
        let status = match m.status {
            TandemSmsStatus::Received => SmsStatus::Received,
            TandemSmsStatus::Sending => SmsStatus::Sending,
            TandemSmsStatus::Sent => SmsStatus::Sent,
            TandemSmsStatus::Failed => SmsStatus::Failed,
        };
        SmsMessage {
            id: m.id,
            mms: m.mms,
            thread: m.thread,
            address: m.address,
            body: m.body,
            date: m.date,
            status,
            read: m.read,
            pictures: m.pictures,
        }
    }
}

impl From<SmsMsg> for TandemSmsEvent {
    fn from(msg: SmsMsg) -> Self {
        let request = |req, request| TandemSmsEvent::Request { req, request };
        match msg {
            SmsMsg::ListConversations { req, before, limit } => request(req, TandemSmsRequest::ListConversations { before, limit }),
            SmsMsg::ListMessages { req, thread, before, limit } => {
                request(req, TandemSmsRequest::ListMessages { thread, before, limit })
            }
            SmsMsg::Send { req, to, text } => request(req, TandemSmsRequest::Send { to, text }),
            SmsMsg::FindContacts { req, query } => request(req, TandemSmsRequest::FindContacts { query }),
            SmsMsg::Picture { req, part } => request(req, TandemSmsRequest::Picture { part }),
            SmsMsg::ListContacts { req, after, limit } => request(req, TandemSmsRequest::ListContacts { after, limit }),
            SmsMsg::AskAccess => TandemSmsEvent::AskAccess,
            SmsMsg::Changed { conversations, messages } => TandemSmsEvent::Changed {
                conversations: conversations.into_iter().map(Into::into).collect(),
                messages: messages.into_iter().map(Into::into).collect(),
            },
            SmsMsg::Conversations { .. }
            | SmsMsg::Messages { .. }
            | SmsMsg::Sent { .. }
            | SmsMsg::Contacts { .. }
            | SmsMsg::PictureData { .. }
            | SmsMsg::ContactPage { .. }
            | SmsMsg::Refused { .. } => TandemSmsEvent::Answered,
        }
    }
}

impl From<PhoneContact> for TandemPhoneContact {
    fn from(p: PhoneContact) -> Self {
        let numbers = p.numbers.into_iter().map(|n| TandemPhoneNumber { number: n.number, label: n.label, preferred: n.preferred }).collect();
        TandemPhoneContact { id: p.id, name: p.name, numbers }
    }
}

impl From<TandemPhoneContact> for PhoneContact {
    fn from(p: TandemPhoneContact) -> Self {
        let numbers = p.numbers.into_iter().map(|n| PhoneNumber { number: n.number, label: n.label, preferred: n.preferred }).collect();
        PhoneContact { id: p.id, name: p.name, numbers }
    }
}

impl From<TandemSmsRefusal> for SmsRefusal {
    fn from(r: TandemSmsRefusal) -> Self {
        match r {
            TandemSmsRefusal::Off => SmsRefusal::Off,
            TandemSmsRefusal::NoPermission => SmsRefusal::NoPermission,
            TandemSmsRefusal::Unavailable => SmsRefusal::Unavailable,
        }
    }
}

fn refused(why: SmsRefusal) -> TandemSmsError {
    match why {
        SmsRefusal::Off => TandemSmsError::Off,
        SmsRefusal::NoPermission => TandemSmsError::NoPermission,
        SmsRefusal::Unavailable | SmsRefusal::Other => TandemSmsError::Unavailable,
    }
}

fn unexpected(answer: SmsMsg) -> TandemSmsError {
    TandemSmsError::Failed { reason: format!("the phone gave an answer to another question: {answer:?}") }
}

fn parse(id: &str) -> Result<DeviceId, TandemError> {
    DeviceId::parse(id).map_err(TandemError::from)
}

// ---- A computer asks ------------------------------------------------------------------

#[uniffi::export(async_runtime = "tokio")]
impl TandemEngine {
    /// A page of the conversations of the phone `target`, newest first.
    pub async fn sms_conversations(&self, target: String, before: u64, limit: u32) -> Result<TandemSmsConversationPage, TandemSmsError> {
        match self.sms_ask(target, move |req| SmsMsg::ListConversations { req, before, limit }).await? {
            SmsMsg::Conversations { items, more, .. } => {
                Ok(TandemSmsConversationPage { items: items.into_iter().map(Into::into).collect(), more })
            }
            other => Err(unexpected(other)),
        }
    }

    /// A page of the messages of one conversation, newest first.
    pub async fn sms_messages(&self, target: String, thread: u64, before: u64, limit: u32) -> Result<TandemSmsMessagePage, TandemSmsError> {
        match self.sms_ask(target, move |req| SmsMsg::ListMessages { req, thread, before, limit }).await? {
            SmsMsg::Messages { thread, items, more, .. } => {
                Ok(TandemSmsMessagePage { thread, items: items.into_iter().map(Into::into).collect(), more })
            }
            other => Err(unexpected(other)),
        }
    }

    /// Sends `text` from the phone to the numbers in `to`. Returns the conversation it went to, when the phone knows it. The
    /// message itself comes as a change, first while it is being sent and again once it is out or failed.
    pub async fn sms_send(&self, target: String, to: Vec<String>, text: String) -> Result<Option<u64>, TandemSmsError> {
        match self.sms_ask(target, move |req| SmsMsg::Send { req, to, text }).await? {
            SmsMsg::Sent { error: Some(reason), .. } => Err(TandemSmsError::Failed { reason }),
            SmsMsg::Sent { thread, .. } => Ok(thread),
            other => Err(unexpected(other)),
        }
    }

    /// Contacts of the phone whose name or number contains `query`, for a new message.
    pub async fn sms_contacts(&self, target: String, query: String) -> Result<Vec<TandemSmsContact>, TandemSmsError> {
        match self.sms_ask(target, move |req| SmsMsg::FindContacts { req, query }).await? {
            SmsMsg::Contacts { items, .. } => {
                Ok(items.into_iter().map(|c| TandemSmsContact { name: c.name, number: c.number, label: c.label }).collect())
            }
            other => Err(unexpected(other)),
        }
    }

    /// Every person with a number in the contacts of the phone `target`, by contact id, for the search of this computer. Only a phone
    /// that says `sms.contacts` answers; ask no other, because an older one stays quiet until the wait runs out.
    pub async fn sms_all_contacts(&self, target: String) -> Result<Vec<TandemPhoneContact>, TandemSmsError> {
        let peer = DeviceId::parse(&target).map_err(|e| TandemSmsError::Failed { reason: e.to_string() })?;
        let engine = self.engine.clone();
        let answer = self
            .runtime
            .spawn(async move { engine.sms_all_contacts(peer).await })
            .await
            .map_err(|e| TandemSmsError::Failed { reason: e.to_string() })?;
        match answer {
            Ok(Ok(people)) => Ok(people.into_iter().map(Into::into).collect()),
            Ok(Err(why)) => Err(refused(why)),
            Err(crate::Error::NotConnected) => Err(TandemSmsError::NotConnected),
            Err(e) => Err(TandemSmsError::Failed { reason: e.to_string() }),
        }
    }

    /// One picture of an MMS, as a small JPEG.
    pub async fn sms_picture(&self, target: String, part: u64) -> Result<Vec<u8>, TandemSmsError> {
        match self.sms_ask(target, move |req| SmsMsg::Picture { req, part }).await? {
            SmsMsg::PictureData { jpeg, .. } => Ok(jpeg.into_vec()),
            other => Err(unexpected(other)),
        }
    }

    /// Asks the person on the phone to let this device in, after a refusal (`Off` or `NoPermission`). The phone shows it;
    /// there is no answer.
    pub async fn sms_ask_access(&self, target: String) -> Result<(), TandemError> {
        let peer = parse(&target)?;
        let engine = self.engine.clone();
        self.runtime.spawn(async move { engine.sms_ask_access(peer).await }).await.map_err(join_error)?.map_err(Into::into)
    }
}

// ---- The phone answers ----------------------------------------------------------------

#[uniffi::export(async_runtime = "tokio")]
impl TandemEngine {
    pub async fn sms_answer_conversations(&self, target: String, req: u64, items: Vec<TandemSmsConversation>, more: bool) -> Result<(), TandemError> {
        self.sms_reply(target, SmsMsg::Conversations { req, items: items.into_iter().map(Into::into).collect(), more }).await
    }

    pub async fn sms_answer_messages(&self, target: String, req: u64, thread: u64, items: Vec<TandemSmsMessage>, more: bool) -> Result<(), TandemError> {
        self.sms_reply(target, SmsMsg::Messages { req, thread, items: items.into_iter().map(Into::into).collect(), more }).await
    }

    /// The text was taken for sending (`error` empty), or why not.
    pub async fn sms_answer_sent(&self, target: String, req: u64, thread: Option<u64>, error: Option<String>) -> Result<(), TandemError> {
        self.sms_reply(target, SmsMsg::Sent { req, thread, error }).await
    }

    pub async fn sms_answer_contacts(&self, target: String, req: u64, items: Vec<TandemSmsContact>) -> Result<(), TandemError> {
        let items = items.into_iter().map(|c| SmsContact { name: c.name, number: c.number, label: c.label }).collect();
        self.sms_reply(target, SmsMsg::Contacts { req, items }).await
    }

    /// A page of the whole list of contacts, for `ListContacts`. `more` when there are people after the last one here.
    pub async fn sms_answer_contact_page(&self, target: String, req: u64, items: Vec<TandemPhoneContact>, more: bool) -> Result<(), TandemError> {
        self.sms_reply(target, SmsMsg::ContactPage { req, items: items.into_iter().map(Into::into).collect(), more }).await
    }

    pub async fn sms_answer_picture(&self, target: String, req: u64, jpeg: Vec<u8>) -> Result<(), TandemError> {
        if jpeg.len() > crate::sms::MAX_PICTURE {
            return Err(TandemError::Failed { reason: "the picture is too big".into() });
        }
        self.sms_reply(target, SmsMsg::PictureData { req, jpeg: serde_bytes::ByteBuf::from(jpeg) }).await
    }

    pub async fn sms_refuse(&self, target: String, req: u64, why: TandemSmsRefusal) -> Result<(), TandemError> {
        self.sms_reply(target, SmsMsg::Refused { req, why: why.into() }).await
    }

    /// Tells the computers that may read along about messages that are new or changed here, with their conversations as they are
    /// now. Returns the devices that got it.
    pub async fn sms_changed(&self, conversations: Vec<TandemSmsConversation>, messages: Vec<TandemSmsMessage>) -> Vec<String> {
        let engine = self.engine.clone();
        let conversations = conversations.into_iter().map(Into::into).collect();
        let messages = messages.into_iter().map(Into::into).collect();
        let reached = self.runtime.spawn(async move { engine.sms_changed(conversations, messages).await }).await.unwrap_or_default();
        reached.into_iter().map(|id| id.to_string()).collect()
    }

    /// Whether the device `id` may read and send the messages of this phone. On unless the person turned it off (or it is a guest).
    pub fn messages_allowed(&self, id: String) -> bool {
        DeviceId::parse(&id).map(|id| self.engine.device_settings(&id).messages).unwrap_or(false)
    }

    pub fn set_messages_allowed(&self, id: String, allowed: bool) -> Result<(), TandemError> {
        let id = parse(&id)?;
        let settings = crate::store::DeviceSettings { messages: allowed, ..self.engine.device_settings(&id) };
        self.engine.set_device_settings(&id, settings).map_err(Into::into)
    }
}

impl TandemEngine {
    async fn sms_ask(&self, target: String, ask: impl FnOnce(u64) -> SmsMsg + Send + 'static) -> Result<SmsMsg, TandemSmsError> {
        let peer = DeviceId::parse(&target).map_err(|e| TandemSmsError::Failed { reason: e.to_string() })?;
        let engine = self.engine.clone();
        let answer = self
            .runtime
            .spawn(async move { engine.sms_ask(peer, ask).await })
            .await
            .map_err(|e| TandemSmsError::Failed { reason: e.to_string() })?;
        match answer {
            Ok(SmsMsg::Refused { why, .. }) => Err(refused(why)),
            Ok(msg) => Ok(msg),
            Err(crate::Error::NotConnected) => Err(TandemSmsError::NotConnected),
            Err(e) => Err(TandemSmsError::Failed { reason: e.to_string() }),
        }
    }

    async fn sms_reply(&self, target: String, answer: SmsMsg) -> Result<(), TandemError> {
        let peer = parse(&target)?;
        let engine = self.engine.clone();
        self.runtime.spawn(async move { engine.sms_answer(peer, answer).await }).await.map_err(join_error)?.map_err(Into::into)
    }
}
