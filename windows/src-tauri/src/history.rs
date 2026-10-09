//! What came to the clipboard of this computer from other devices, and what this computer copied: a list to take an
//! earlier text from again. It is kept in a file in the data folder of the app and never leaves the computer.

use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

/// How many texts the tests keep. A pinned one stays whatever the number.
#[cfg(test)]
const LIMIT: usize = 100;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Item {
    pub id: u64,
    pub text: String,
    /// The name of the device it came from, empty for what was copied here.
    pub from: String,
    pub at_ms: u64,
    pub pinned: bool,
}

static ITEMS: Mutex<Option<Vec<Item>>> = Mutex::new(None);

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// The list with `text` put at the top. The same text again moves up, keeps its pin and its source when no new one is given.
#[cfg(test)]
pub fn add(list: &[Item], text: &str, from: &str, now: u64) -> Vec<Item> {
    add_within(list, text, from, now, LIMIT)
}

/// The same, with the number of texts that is kept.
pub fn add_within(list: &[Item], text: &str, from: &str, now: u64, limit: usize) -> Vec<Item> {
    let earlier = list.iter().find(|i| i.text == text);
    let item = Item {
        id: now.max(list.iter().map(|i| i.id).max().unwrap_or(0) + 1),
        text: text.to_string(),
        from: if from.is_empty() { earlier.map(|e| e.from.clone()).unwrap_or_default() } else { from.to_string() },
        at_ms: now,
        pinned: earlier.is_some_and(|e| e.pinned),
    };
    let rest: Vec<Item> = list.iter().filter(|i| i.text != text).cloned().collect();
    let pinned = rest.iter().filter(|i| i.pinned).count();
    let keep_unpinned = limit.saturating_sub(1 + pinned);
    let mut seen = 0;
    let rest: Vec<Item> = rest
        .into_iter()
        .filter(|i| {
            if i.pinned {
                return true;
            }
            seen += 1;
            seen <= keep_unpinned
        })
        .collect();
    let mut out = vec![item];
    out.extend(rest);
    out
}

/// Words that all have to be in the text or in the name of the device, in any case.
pub fn search(list: &[Item], query: &str) -> Vec<Item> {
    let words: Vec<String> = query.to_lowercase().split_whitespace().map(str::to_string).collect();
    list.iter()
        .filter(|i| words.iter().all(|w| i.text.to_lowercase().contains(w) || i.from.to_lowercase().contains(w)))
        .cloned()
        .collect()
}

fn path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path().app_data_dir().ok().map(|d| d.join("clip_history.json"))
}

fn load(app: &AppHandle) -> Vec<Item> {
    path(app)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn change(app: &AppHandle, edit: impl FnOnce(Vec<Item>) -> Vec<Item>) -> Vec<Item> {
    let mut guard = ITEMS.lock().unwrap();
    let current = guard.take().unwrap_or_else(|| load(app));
    let next = edit(current);
    if let Some(p) = path(app) {
        let _ = std::fs::write(p, serde_json::to_string(&next).unwrap_or_default());
    }
    *guard = Some(next.clone());
    drop(guard);
    let _ = app.emit("clip-history", json!(next));
    next
}

/// A text that was copied here or came from another device.
pub fn record(app: &AppHandle, text: &str, from: &str) {
    let current = crate::settings::get(app);
    if text.trim().is_empty() || !current.clip_history {
        return;
    }
    let now = now_ms();
    let (limit, days) = (current.clip_limit.max(10) as usize, u64::from(current.clip_days.max(1)));
    change(app, |list| expire(add_within(&list, text, from, now, limit), now, days));
}

/// What is older than the days that are kept goes, except what is pinned.
pub fn expire(list: Vec<Item>, now: u64, days: u64) -> Vec<Item> {
    let oldest = now.saturating_sub(days * 24 * 3600 * 1000);
    list.into_iter().filter(|i| i.pinned || i.at_ms >= oldest).collect()
}

/// How much is saved, for the settings.
#[tauri::command]
pub fn clip_history_info(app: AppHandle) -> Value {
    let mut guard = ITEMS.lock().unwrap();
    let list = guard.get_or_insert_with(|| load(&app));
    let bytes: usize = list.iter().map(|i| i.text.len()).sum();
    json!({ "count": list.len(), "pinned": list.iter().filter(|i| i.pinned).count(), "bytes": bytes })
}

#[tauri::command]
pub fn clip_history(app: AppHandle) -> Value {
    let mut guard = ITEMS.lock().unwrap();
    let list = guard.get_or_insert_with(|| load(&app)).clone();
    json!(list)
}

/// The list narrowed to what matches the words, pinned first.
#[tauri::command]
pub fn clip_history_search(app: AppHandle, query: String) -> Value {
    let mut guard = ITEMS.lock().unwrap();
    let list = guard.get_or_insert_with(|| load(&app)).clone();
    let mut found = search(&list, &query);
    found.sort_by_key(|i| !i.pinned);
    json!(found)
}

#[tauri::command]
pub fn clip_history_pin(app: AppHandle, id: u64) {
    change(&app, |list| list.into_iter().map(|i| if i.id == id { Item { pinned: !i.pinned, ..i } } else { i }).collect());
}

#[tauri::command]
pub fn clip_history_remove(app: AppHandle, id: u64) {
    change(&app, |list| list.into_iter().filter(|i| i.id != id).collect());
}

/// Everything goes, or everything but what is pinned.
#[tauri::command]
pub fn clip_history_clear(app: AppHandle, keep_pinned: bool) {
    change(&app, |list| if keep_pinned { list.into_iter().filter(|i| i.pinned).collect() } else { Vec::new() });
}

/// Puts a text from the list on the clipboard again, without sending it to the other devices.
#[tauri::command]
pub fn clip_history_copy(app: AppHandle, id: u64) -> Result<(), String> {
    let text = ITEMS
        .lock()
        .unwrap()
        .as_ref()
        .and_then(|list| list.iter().find(|i| i.id == id).map(|i| i.text.clone()))
        .ok_or("that text is no longer in the list")?;
    crate::clip::apply(&app, &text);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(text: &str, at: u64) -> Item {
        Item { id: at, text: text.into(), from: String::new(), at_ms: at, pinned: false }
    }

    #[test]
    fn the_newest_is_on_top() {
        let list = add(&add(&[], "one", "Phone", 1000), "two", "Phone", 2000);
        assert_eq!(list.iter().map(|i| i.text.as_str()).collect::<Vec<_>>(), ["two", "one"]);
    }

    #[test]
    fn the_same_text_moves_up_and_keeps_its_pin_and_source() {
        let mut list = add(&[], "link", "Phone", 1000);
        list[0].pinned = true;
        let list = add(&list, "other", "Phone", 2000);
        let list = add(&list, "link", "", 3000);
        assert_eq!(list.iter().map(|i| i.text.as_str()).collect::<Vec<_>>(), ["link", "other"]);
        assert!(list[0].pinned);
        assert_eq!(list[0].from, "Phone");
    }

    #[test]
    fn the_oldest_go_after_the_limit_and_a_pin_stays() {
        let mut list = add(&[], "keep me", "", 0);
        list[0].pinned = true;
        for n in 1..=150u64 {
            list = add(&list, &format!("item {n}"), "", n * 10);
        }
        assert_eq!(list.len(), LIMIT);
        assert!(list.iter().any(|i| i.text == "keep me"));
        assert_eq!(list[0].text, "item 150");
    }

    #[test]
    fn what_is_older_than_the_days_goes_but_a_pin_stays() {
        let day = 24 * 3600 * 1000;
        let mut old = item("old", 1);
        let mut pinned = item("pinned", 2);
        pinned.pinned = true;
        let fresh = item("fresh", 40 * day);
        old.at_ms = 1;
        let kept = expire(vec![fresh.clone(), pinned.clone(), old], 41 * day, 30);
        assert_eq!(kept, vec![fresh, pinned]);
    }

    #[test]
    fn the_number_that_is_kept_follows_the_setting() {
        let mut list = Vec::new();
        for n in 0..30u64 {
            list = add_within(&list, &format!("text {n}"), "", n, 10);
        }
        assert_eq!(list.len(), 10);
        assert_eq!(list[0].text, "text 29");
    }

    #[test]
    fn search_needs_every_word() {
        let list = vec![
            Item { from: "Mac".into(), ..item("meeting at ten", 1) },
            Item { from: "Phone".into(), ..item("pizza at ten", 2) },
        ];
        assert_eq!(search(&list, "TEN pizza").len(), 1);
        assert_eq!(search(&list, "mac")[0].text, "meeting at ten");
        assert_eq!(search(&list, "  ").len(), 2);
    }

    #[test]
    fn what_was_saved_comes_back() {
        let list = vec![Item { text: "a \"quoted\" line\nand more".into(), pinned: true, ..item("x", 5) }];
        let text = serde_json::to_string(&list).unwrap();
        assert_eq!(serde_json::from_str::<Vec<Item>>(&text).unwrap(), list);
    }
}
