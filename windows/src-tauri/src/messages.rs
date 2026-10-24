//! Messages: the text messages of a phone, for the page that shows them. The page asks through these commands; what the
//! phone tells by itself afterwards (a message that is new or changed) comes to it as the event "sms".
//!
//! A refusal reaches the page as one of the words `off`, `no_permission`, `unavailable` or `offline`, so it can say in its
//! own language what to do. Anything else is the reason itself.

use base64::Engine as _;
use serde_json::{Value, json};
use tandem_core::ffi::sms::{TandemSmsContact, TandemSmsConversation, TandemSmsError, TandemSmsEvent, TandemSmsMessage, TandemSmsStatus};
use tauri::{AppHandle, Emitter, State};

use crate::state::AppState;

type Reply<T> = Result<T, String>;

fn refusal(e: TandemSmsError) -> String {
    match e {
        TandemSmsError::Off => "off".into(),
        TandemSmsError::NoPermission => "no_permission".into(),
        TandemSmsError::Unavailable => "unavailable".into(),
        TandemSmsError::NotConnected => "offline".into(),
        TandemSmsError::Failed { reason } => reason,
    }
}

fn conversation(c: &TandemSmsConversation) -> Value {
    json!({
        "thread": c.thread,
        "addresses": c.addresses,
        "name": c.name,
        "snippet": c.snippet,
        "date": c.date,
        "unread": c.unread,
        "fromMe": c.from_me,
    })
}

fn message(m: &TandemSmsMessage) -> Value {
    let status = match m.status {
        TandemSmsStatus::Received => "received",
        TandemSmsStatus::Sending => "sending",
        TandemSmsStatus::Sent => "sent",
        TandemSmsStatus::Failed => "failed",
    };
    json!({
        "id": m.id,
        "mms": m.mms,
        "thread": m.thread,
        "address": m.address,
        "body": m.body,
        "date": m.date,
        "status": status,
        "read": m.read,
        "pictures": m.pictures,
    })
}

fn contact(c: &TandemSmsContact) -> Value {
    json!({ "name": c.name, "number": c.number, "label": c.label })
}

/// A page of the conversations of the phone `id`, newest first.
#[tauri::command]
pub async fn sms_conversations(state: State<'_, AppState>, id: String, before: u64, limit: u32) -> Reply<Value> {
    let page = state.engine()?.sms_conversations(id, before, limit).await.map_err(refusal)?;
    Ok(json!({ "items": page.items.iter().map(conversation).collect::<Vec<_>>(), "more": page.more }))
}

/// A page of the messages of one conversation, newest first.
#[tauri::command]
pub async fn sms_messages(state: State<'_, AppState>, id: String, thread: u64, before: u64, limit: u32) -> Reply<Value> {
    let page = state.engine()?.sms_messages(id, thread, before, limit).await.map_err(refusal)?;
    Ok(json!({ "thread": page.thread, "items": page.items.iter().map(message).collect::<Vec<_>>(), "more": page.more }))
}

/// Sends a text from the phone. Returns the conversation it went to, when the phone knows it.
#[tauri::command]
pub async fn sms_send(state: State<'_, AppState>, id: String, to: Vec<String>, text: String) -> Reply<Option<u64>> {
    state.engine()?.sms_send(id, to, text).await.map_err(refusal)
}

#[tauri::command]
pub async fn sms_contacts(state: State<'_, AppState>, id: String, query: String) -> Reply<Value> {
    let found = state.engine()?.sms_contacts(id, query).await.map_err(refusal)?;
    Ok(Value::Array(found.iter().map(contact).collect()))
}

/// A picture of an MMS as something an image can show, or nothing when the phone has none to give.
#[tauri::command]
pub async fn sms_picture(state: State<'_, AppState>, id: String, part: u64) -> Reply<Option<String>> {
    let jpeg = state.engine()?.sms_picture(id, part).await.map_err(refusal)?;
    Ok((!jpeg.is_empty()).then(|| format!("data:image/jpeg;base64,{}", base64::engine::general_purpose::STANDARD.encode(jpeg))))
}

/// Asks the person on the phone to let this computer read and send its messages.
#[tauri::command]
pub async fn sms_ask_access(state: State<'_, AppState>, id: String) -> Reply<()> {
    state.engine()?.sms_ask_access(id).await.map_err(|e| e.to_string())
}

/// What the phone tells by itself, on to the page.
pub fn event(app: &AppHandle, from: &str, msg: TandemSmsEvent) {
    if let TandemSmsEvent::Changed { conversations, messages } = msg {
        let _ = app.emit(
            "sms",
            json!({
                "device": from,
                "conversations": conversations.iter().map(conversation).collect::<Vec<_>>(),
                "messages": messages.iter().map(message).collect::<Vec<_>>(),
            }),
        );
    }
}
