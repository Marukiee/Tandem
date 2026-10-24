//! Messages: the SMS conversations of a phone, read and answered on a computer.
//!
//! The computer asks (`ListConversations`, `ListMessages`, `Send`, `FindContacts`, `Picture`) and the phone answers with
//! the same `req`. The phone also tells every computer that may read along about each message that is new or changed
//! (`Changed`), whoever wrote it. Lists come in pages, newest first, so a thread of years never travels at once.
//!
//! The phone decides who may: a request of a device whose `messages` setting is off is refused here, in the core, before
//! the app hears of it, and only devices that say `sms.view` and are allowed get the changes.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_bytes::ByteBuf;
use tokio::sync::broadcast::error::RecvError;

use crate::engine::{Engine, Inner};
use crate::error::{Error, Result};
use crate::events::Event;
use crate::ids::DeviceId;
use crate::proto::Msg;

/// What a phone that can do messages says it can do.
pub const CAP_PHONE: &str = "sms";
/// What a computer that shows them says, so the phone sends it the changes.
pub const CAP_VIEW: &str = "sms.view";

/// A page is never longer than this, whatever is asked.
pub const MAX_PAGE: u32 = 100;
/// What a page is when the asker leaves the length open.
pub const DEFAULT_PAGE: u32 = 40;
/// The text of one message, in bytes. An MMS can carry a long text; past this it is cut.
pub const MAX_BODY: usize = 16 * 1024;
pub const MAX_SNIPPET: usize = 200;
/// What a computer may send in one go, in characters. The phone cuts it into as many SMS as it takes.
pub const MAX_SEND: usize = 5000;
pub const MAX_QUERY: usize = 100;
pub const MAX_CONTACTS: usize = 25;
/// The people in one conversation. More is a broadcast list, not a conversation.
pub const MAX_ADDRESSES: usize = 20;
pub const MAX_PICTURES: usize = 10;
pub const MAX_PICTURE: usize = 512 * 1024;
/// How long a computer waits for the phone to answer.
pub const ANSWER_WAIT: Duration = Duration::from_secs(20);
/// How often a computer may ask the person on the phone to let it in.
pub const ASK_EVERY: Duration = Duration::from_secs(30);

/// A conversation as the list on the left shows it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsConversation {
    /// The thread of the conversation on the phone.
    pub thread: u64,
    /// The numbers of the others in it, more than one for a group.
    #[serde(default)]
    pub addresses: Vec<String>,
    /// What the contacts of the phone call them, joined. Empty when none of them is known.
    #[serde(default)]
    pub name: String,
    /// The start of the last message.
    #[serde(default)]
    pub snippet: String,
    /// When the last message was, in milliseconds since 1970.
    pub date: u64,
    #[serde(default)]
    pub unread: u32,
    /// The last message was sent by the phone.
    #[serde(default)]
    pub from_me: bool,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SmsStatus {
    /// It came in.
    #[default]
    Received,
    /// The phone is sending it.
    Sending,
    Sent,
    /// It could not be sent.
    Failed,
    #[serde(other)]
    Other,
}

/// One message of a conversation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsMessage {
    /// The row of the message on the phone. SMS and MMS are counted apart, so `mms` is part of the id.
    pub id: u64,
    #[serde(default)]
    pub mms: bool,
    pub thread: u64,
    /// Who sent it, for one that came in. Empty for what the phone sent.
    #[serde(default)]
    pub address: String,
    #[serde(default)]
    pub body: String,
    pub date: u64,
    #[serde(default)]
    pub status: SmsStatus,
    #[serde(default)]
    pub read: bool,
    /// The pictures of an MMS, by the part that holds each. Ask for one with `Picture`.
    #[serde(default)]
    pub pictures: Vec<u64>,
}

impl SmsMessage {
    /// The phone sent it (or tried to), as opposed to receiving it.
    pub fn from_me(&self) -> bool {
        self.status != SmsStatus::Received
    }
}

/// Someone in the contacts of the phone, for a new message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsContact {
    pub name: String,
    pub number: String,
    /// What kind of number it is ("Mobile", "Work"), in the language of the phone.
    #[serde(default)]
    pub label: String,
}

/// Why the phone will not do what was asked.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SmsRefusal {
    /// Messages is switched off for the asking device on the phone.
    Off,
    /// The phone has not let Tandem read or send its messages.
    NoPermission,
    /// This device has no messages to give (not a phone, or no SIM).
    Unavailable,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SmsMsg {
    // A computer asks the phone.
    /// The conversations at or before `before` (milliseconds, 0 for the newest), newest first.
    ListConversations {
        req: u64,
        #[serde(default)]
        before: u64,
        #[serde(default)]
        limit: u32,
    },
    /// The messages of one conversation at or before `before`, newest first. "At" so that nothing with the same time as the
    /// last one of the page before is lost; the asker leaves out what it has.
    ListMessages {
        req: u64,
        thread: u64,
        #[serde(default)]
        before: u64,
        #[serde(default)]
        limit: u32,
    },
    /// Sends `text` as SMS to the numbers in `to`.
    Send { req: u64, to: Vec<String>, text: String },
    /// Contacts whose name or number contains `query`.
    FindContacts { req: u64, query: String },
    /// One picture of an MMS, made small.
    Picture { req: u64, part: u64 },
    /// Asks the person on the phone to let this device in: the switch for it, or the permission of Android. The only request a
    /// device that is switched off may make, and one without an answer.
    AskAccess,

    // The phone answers, with the `req` of the question.
    Conversations {
        req: u64,
        items: Vec<SmsConversation>,
        #[serde(default)]
        more: bool,
    },
    Messages {
        req: u64,
        thread: u64,
        items: Vec<SmsMessage>,
        #[serde(default)]
        more: bool,
    },
    /// The phone took the text. Whether it really went out shows in the message itself, which comes as `Changed`. `thread` is the
    /// conversation it went to, when the phone knows it.
    Sent {
        req: u64,
        #[serde(default)]
        thread: Option<u64>,
        #[serde(default)]
        error: Option<String>,
    },
    Contacts { req: u64, items: Vec<SmsContact> },
    PictureData { req: u64, jpeg: ByteBuf },
    Refused { req: u64, why: SmsRefusal },

    // The phone, by itself.
    /// Messages that are new or changed, and the conversations they are in as they are now.
    Changed {
        #[serde(default)]
        conversations: Vec<SmsConversation>,
        #[serde(default)]
        messages: Vec<SmsMessage>,
    },
}

impl SmsMsg {
    /// The id of a question to the phone, or `None` for anything else.
    pub fn request(&self) -> Option<u64> {
        match self {
            SmsMsg::ListConversations { req, .. }
            | SmsMsg::ListMessages { req, .. }
            | SmsMsg::Send { req, .. }
            | SmsMsg::FindContacts { req, .. }
            | SmsMsg::Picture { req, .. } => Some(*req),
            _ => None,
        }
    }

    /// Whether this is the phone's answer to the question `id`.
    pub fn answers(&self, id: u64) -> bool {
        match self {
            SmsMsg::Conversations { req, .. }
            | SmsMsg::Messages { req, .. }
            | SmsMsg::Sent { req, .. }
            | SmsMsg::Contacts { req, .. }
            | SmsMsg::PictureData { req, .. }
            | SmsMsg::Refused { req, .. } => *req == id,
            _ => false,
        }
    }

    /// Holds everything to the limits above, on the way out and on the way in, so a page stays a page and a bug on one side
    /// never floods the other.
    pub fn tidy(self) -> SmsMsg {
        match self {
            SmsMsg::ListConversations { req, before, limit } => SmsMsg::ListConversations { req, before, limit: page(limit) },
            SmsMsg::ListMessages { req, thread, before, limit } => SmsMsg::ListMessages { req, thread, before, limit: page(limit) },
            SmsMsg::Send { req, mut to, text } => {
                to.truncate(MAX_ADDRESSES);
                to.iter_mut().for_each(|n| cut(n, 64));
                SmsMsg::Send { req, to, text }
            }
            SmsMsg::FindContacts { req, mut query } => {
                cut(&mut query, MAX_QUERY);
                SmsMsg::FindContacts { req, query }
            }
            SmsMsg::Conversations { req, mut items, more } => {
                items.truncate(MAX_PAGE as usize);
                items.iter_mut().for_each(tidy_conversation);
                SmsMsg::Conversations { req, items, more }
            }
            SmsMsg::Messages { req, thread, mut items, more } => {
                items.truncate(MAX_PAGE as usize);
                items.iter_mut().for_each(tidy_message);
                SmsMsg::Messages { req, thread, items, more }
            }
            SmsMsg::Contacts { req, mut items } => {
                items.truncate(MAX_CONTACTS);
                for contact in &mut items {
                    cut(&mut contact.name, 200);
                    cut(&mut contact.number, 64);
                    cut(&mut contact.label, 64);
                }
                SmsMsg::Contacts { req, items }
            }
            SmsMsg::Sent { req, thread, mut error } => {
                if let Some(text) = error.as_mut() {
                    cut(text, 500);
                }
                SmsMsg::Sent { req, thread, error }
            }
            SmsMsg::Changed { mut conversations, mut messages } => {
                conversations.truncate(MAX_PAGE as usize);
                conversations.iter_mut().for_each(tidy_conversation);
                messages.truncate(MAX_PAGE as usize);
                messages.iter_mut().for_each(tidy_message);
                SmsMsg::Changed { conversations, messages }
            }
            other => other,
        }
    }
}

fn page(limit: u32) -> u32 {
    if limit == 0 { DEFAULT_PAGE } else { limit.min(MAX_PAGE) }
}

fn tidy_conversation(c: &mut SmsConversation) {
    c.addresses.truncate(MAX_ADDRESSES);
    c.addresses.iter_mut().for_each(|a| cut(a, 64));
    cut(&mut c.name, 500);
    cut(&mut c.snippet, MAX_SNIPPET);
}

fn tidy_message(m: &mut SmsMessage) {
    cut(&mut m.address, 64);
    cut(&mut m.body, MAX_BODY);
    m.pictures.truncate(MAX_PICTURES);
}

/// Cuts `text` to at most `max` bytes, at the edge of a character.
fn cut(text: &mut String, max: usize) {
    if text.len() <= max {
        return;
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text.truncate(end);
}

/// Why a text cannot be sent as it is, or `None` when it can.
pub fn send_problem(to: &[String], text: &str) -> Option<&'static str> {
    if to.iter().all(|n| n.trim().is_empty()) {
        Some("no number to send to")
    } else if text.trim().is_empty() {
        Some("nothing to send")
    } else if text.chars().count() > MAX_SEND {
        Some("the text is too long")
    } else {
        None
    }
}

impl Engine {
    /// Asks the phone `peer` something and waits for its answer, which may be a `Refused`. `ask` gets the id the answer will carry.
    pub async fn sms_ask(&self, peer: DeviceId, ask: impl FnOnce(u64) -> SmsMsg) -> Result<SmsMsg> {
        let id = crate::engine::rand_u64();
        let question = ask(id).tidy();
        if let SmsMsg::Send { to, text, .. } = &question {
            if let Some(problem) = send_problem(to, text) {
                return Err(Error::invalid(problem));
            }
        }
        // Listening before asking, so an answer that is quick is not missed.
        let mut events = self.subscribe();
        self.inner.send_to(&peer, Msg::Sms(question)).await?;
        let wait = async {
            loop {
                match events.recv().await {
                    Ok(Event::Sms { from, msg }) if from == peer && msg.answers(id) => return Ok(msg),
                    Ok(Event::Disconnected { id: gone }) if gone == peer => return Err(Error::NotConnected),
                    Ok(_) | Err(RecvError::Lagged(_)) => {}
                    Err(RecvError::Closed) => return Err(Error::Cancelled),
                }
            }
        };
        tokio::time::timeout(ANSWER_WAIT, wait).await.map_err(|_| Error::connection("the phone did not answer"))?
    }

    /// The phone's answer to a question of `peer`.
    pub async fn sms_answer(&self, peer: DeviceId, answer: SmsMsg) -> Result<()> {
        self.inner.send_to(&peer, Msg::Sms(answer.tidy())).await
    }

    /// Asks the person on the phone `peer` to let this device read and send its messages.
    pub async fn sms_ask_access(&self, peer: DeviceId) -> Result<()> {
        self.inner.send_to(&peer, Msg::Sms(SmsMsg::AskAccess)).await
    }

    /// The devices that hear about every change: connected, showing messages, and allowed to.
    pub fn sms_readers(&self) -> Vec<DeviceId> {
        self.devices()
            .into_iter()
            .filter(|d| d.online && d.settings.messages && d.caps.iter().any(|c| c == CAP_VIEW))
            .map(|d| d.id)
            .collect()
    }

    /// Tells the readers about messages that are new or changed on this phone. Returns who got it.
    pub async fn sms_changed(&self, conversations: Vec<SmsConversation>, messages: Vec<SmsMessage>) -> Vec<DeviceId> {
        let readers = self.sms_readers();
        if readers.is_empty() || (conversations.is_empty() && messages.is_empty()) {
            return Vec::new();
        }
        let change = SmsMsg::Changed { conversations, messages }.tidy();
        self.inner.send_many(&readers, Msg::Sms(change)).await
    }
}

impl Inner {
    pub(crate) async fn on_sms(self: &Arc<Self>, from: DeviceId, msg: SmsMsg) {
        let msg = msg.tidy();
        if matches!(msg, SmsMsg::AskAccess) {
            if self.cfg.caps.iter().any(|c| c == CAP_PHONE) && self.sms_ask_allowed(from) {
                self.emit(Event::Sms { from, msg });
            }
            return;
        }
        if let Some(req) = msg.request() {
            let refusal = if !self.cfg.caps.iter().any(|c| c == CAP_PHONE) {
                Some(SmsRefusal::Unavailable)
            } else if !self.settings.read().unwrap().for_device(&from).messages {
                Some(SmsRefusal::Off)
            } else {
                None
            };
            if let Some(why) = refusal {
                let _ = self.send_to(&from, Msg::Sms(SmsMsg::Refused { req, why })).await;
                return;
            }
            if let SmsMsg::Send { to, text, .. } = &msg {
                if let Some(problem) = send_problem(to, text) {
                    let answer = SmsMsg::Sent { req, thread: None, error: Some(problem.into()) };
                    let _ = self.send_to(&from, Msg::Sms(answer)).await;
                    return;
                }
            }
        }
        self.emit(Event::Sms { from, msg });
    }

    /// A device that keeps asking is not put in front of the person every time.
    fn sms_ask_allowed(&self, from: DeviceId) -> bool {
        // Kept by the pair of devices, so two engines in one process (the tests) do not share it.
        static ASKED: std::sync::Mutex<Vec<(DeviceId, DeviceId, Instant)>> = std::sync::Mutex::new(Vec::new());
        let mut asked = ASKED.lock().unwrap();
        asked.retain(|(_, _, at)| at.elapsed() < ASK_EVERY);
        if asked.iter().any(|(me, id, _)| *me == self.my_id && *id == from) {
            return false;
        }
        asked.push((self.my_id, from, Instant::now()));
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{decode_msg, encode};

    fn message(id: u64, body: &str) -> SmsMessage {
        SmsMessage {
            id,
            mms: false,
            thread: 3,
            address: "+31600000000".into(),
            body: body.into(),
            date: 1_700_000_000_000 + id,
            status: SmsStatus::Received,
            read: false,
            pictures: vec![],
        }
    }

    #[test]
    fn questions_and_answers_round_trip() {
        let ask = Msg::Sms(SmsMsg::ListMessages { req: 9, thread: 3, before: 0, limit: 20 });
        let Msg::Sms(back) = decode_msg(&encode(&ask).unwrap()).unwrap() else { panic!("not sms") };
        assert_eq!(back.request(), Some(9));
        assert!(!back.answers(9));

        let answer = Msg::Sms(SmsMsg::Messages { req: 9, thread: 3, items: vec![message(1, "hoi")], more: true });
        let Msg::Sms(back) = decode_msg(&encode(&answer).unwrap()).unwrap() else { panic!("not sms") };
        assert!(back.answers(9) && !back.answers(10));
        assert_eq!(back.request(), None);
        match back {
            SmsMsg::Messages { items, more, .. } => assert_eq!((items[0].body.as_str(), more), ("hoi", true)),
            other => panic!("wrong answer: {other:?}"),
        }
    }

    #[test]
    fn a_kind_from_the_future_is_skipped_and_a_status_from_the_future_reads_as_other() {
        // A request a newer computer might send: the whole message is skipped, not the connection.
        let future = ciborium::Value::Map(vec![(
            ciborium::Value::Text("Sms".into()),
            ciborium::Value::Map(vec![(
                ciborium::Value::Text("Teleport".into()),
                ciborium::Value::Map(vec![(ciborium::Value::Text("req".into()), ciborium::Value::Integer(1.into()))]),
            )]),
        )]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&future, &mut bytes).unwrap();
        assert!(matches!(decode_msg(&bytes).unwrap(), Msg::Unknown));

        let status = ciborium::Value::Text("delivered".into());
        let mut bytes = Vec::new();
        ciborium::into_writer(&status, &mut bytes).unwrap();
        let read: SmsStatus = crate::proto::decode(&bytes).unwrap();
        assert_eq!(read, SmsStatus::Other);
    }

    #[test]
    fn a_message_without_the_later_fields_still_reads() {
        // How a first version of the phone might have written it: no mms, read or pictures.
        let old = ciborium::Value::Map(vec![
            (ciborium::Value::Text("id".into()), ciborium::Value::Integer(5.into())),
            (ciborium::Value::Text("thread".into()), ciborium::Value::Integer(2.into())),
            (ciborium::Value::Text("date".into()), ciborium::Value::Integer(7.into())),
        ]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&old, &mut bytes).unwrap();
        let m: SmsMessage = crate::proto::decode(&bytes).unwrap();
        assert_eq!((m.id, m.mms, m.status, m.pictures.len()), (5, false, SmsStatus::Received, 0));
        assert!(!m.from_me());
    }

    #[test]
    fn pages_are_held_to_their_limits() {
        let SmsMsg::ListConversations { limit, .. } = (SmsMsg::ListConversations { req: 1, before: 0, limit: 0 }).tidy() else { panic!() };
        assert_eq!(limit, DEFAULT_PAGE);
        let SmsMsg::ListMessages { limit, .. } = (SmsMsg::ListMessages { req: 1, thread: 1, before: 0, limit: 5000 }).tidy() else { panic!() };
        assert_eq!(limit, MAX_PAGE);

        let long = "é".repeat(MAX_BODY);
        let items: Vec<SmsMessage> = (0..150).map(|i| message(i, &long)).collect();
        let SmsMsg::Messages { items, .. } = (SmsMsg::Messages { req: 1, thread: 3, items, more: true }).tidy() else { panic!() };
        assert_eq!(items.len(), MAX_PAGE as usize);
        // Cut at the edge of a character, never in the middle of one.
        assert!(items[0].body.len() <= MAX_BODY && items[0].body.chars().all(|c| c == 'é'));
    }

    #[test]
    fn what_cannot_be_sent_is_said_before_it_goes() {
        let to = vec!["+31612345678".to_string()];
        assert_eq!(send_problem(&to, "hoi"), None);
        assert!(send_problem(&[], "hoi").is_some());
        assert!(send_problem(&[" ".into()], "hoi").is_some());
        assert!(send_problem(&to, "  ").is_some());
        assert!(send_problem(&to, &"a".repeat(MAX_SEND + 1)).is_some());
        assert_eq!(send_problem(&to, &"ä".repeat(MAX_SEND)), None);
    }
}
