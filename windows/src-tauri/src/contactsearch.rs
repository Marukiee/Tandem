//! The contacts of the paired phones in the search of the desktop: type a name in the GNOME overview (or KRunner on KDE), pick the
//! person, and the phone calls them. The list is fetched from each phone that says `sms.contacts` when it connects and again every
//! few hours, and only kept in memory: the search asks this app while it runs and Tandem is not started for it. The parts of the
//! desktop are in `tandem_winsys::searchprovider`, offered by `desktopsearch.rs` with the other kinds of results; what is found of the
//! contacts and what a pick does is here, so it is tested on any system.
//! Windows has no way for an app to put results in the search of the Start menu (see docs/PARITY.md).
#![cfg_attr(not(target_os = "linux"), allow(dead_code))]

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tandem_core::ffi::sms::{TandemPhoneContact, TandemSmsError};
use tauri::{AppHandle, Emitter, Manager};

use crate::state::AppState;
use crate::{events, i18n, settings};

const REFRESH_EVERY: Duration = Duration::from_secs(3 * 3600);
const RETRY_AFTER: Duration = Duration::from_secs(10 * 60);
/// What the overview shows of one search at most; more is a list nobody reads.
const MAX_RESULTS: usize = 8;

/// The people of each phone, by the id of the phone.
static BOOK: Mutex<Vec<(String, Vec<TandemPhoneContact>)>> = Mutex::new(Vec::new());
/// When each phone was last asked, or is being asked (`None` while the question runs).
static ASKED: Mutex<Option<HashMap<String, Option<Instant>>>> = Mutex::new(None);
/// A person to show in the Call box once the window is there, for a window that had to be made first.
static PENDING: Mutex<Option<Value>> = Mutex::new(None);

/// What picking a person does.
#[derive(Debug, PartialEq, Eq)]
pub enum Pick {
    Call { phone: String, number: String },
    /// Several numbers and none to call without asking: the Call box of that phone, with the name in it.
    Ask { phone: String, name: String },
}

fn result_id(phone: &str, person: u64) -> String {
    format!("{phone}/{person}")
}

fn parse(id: &str) -> Option<(&str, u64)> {
    let (phone, person) = id.rsplit_once('/')?;
    Some((phone, person.parse().ok()?))
}

fn digits(text: &str) -> String {
    text.chars().filter(char::is_ascii_digit).collect()
}

/// Whether every one of `terms` is in the name (at the start of a word) or, for a term of digits, in one of the numbers.
fn matches(person: &TandemPhoneContact, terms: &[String]) -> bool {
    let name = person.name.to_lowercase();
    let words: Vec<&str> = name.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).collect();
    terms.iter().all(|term| {
        let term = term.to_lowercase();
        if term.is_empty() {
            return true;
        }
        let wanted = digits(&term);
        if wanted.len() >= 3 && wanted.len() == term.trim_start_matches('+').len() {
            return person.numbers.iter().any(|n| digits(&n.number).contains(&wanted));
        }
        words.iter().any(|w| w.starts_with(&term))
    })
}

/// The results for `terms`, best first: a name that starts with what was typed before one where a later word does.
pub fn find(book: &[(String, Vec<TandemPhoneContact>)], terms: &[String]) -> Vec<String> {
    let first = terms.first().map(|t| t.to_lowercase()).unwrap_or_default();
    let mut hits: Vec<(bool, String, String)> = book
        .iter()
        .flat_map(|(phone, people)| people.iter().map(move |p| (phone, p)))
        .filter(|(_, p)| matches(p, terms))
        .map(|(phone, p)| (!p.name.to_lowercase().starts_with(&first), p.name.to_lowercase(), result_id(phone, p.id)))
        .collect();
    hits.sort();
    hits.dedup_by(|a, b| a.2 == b.2);
    hits.into_iter().take(MAX_RESULTS).map(|(_, _, id)| id).collect()
}

fn person<'a>(book: &'a [(String, Vec<TandemPhoneContact>)], id: &str) -> Option<(&'a str, &'a TandemPhoneContact)> {
    let (phone, wanted) = parse(id)?;
    let (phone, people) = book.iter().find(|(p, _)| p == phone)?;
    Some((phone.as_str(), people.iter().find(|p| p.id == wanted)?))
}

/// The name and the line under it for each of `ids`: the numbers, with their kind.
pub fn metas(book: &[(String, Vec<TandemPhoneContact>)], ids: &[String]) -> Vec<(String, String, String)> {
    ids.iter()
        .filter_map(|id| {
            let (_, p) = person(book, id)?;
            let numbers = p
                .numbers
                .iter()
                .map(|n| if n.label.is_empty() { n.number.clone() } else { format!("{} {}", n.label, n.number) })
                .collect::<Vec<_>>()
                .join(", ");
            Some((id.clone(), p.name.clone(), numbers))
        })
        .collect()
}

/// One number, or the one the phone marked to call without asking, is called at once; otherwise the person chooses.
pub fn pick(book: &[(String, Vec<TandemPhoneContact>)], id: &str) -> Option<Pick> {
    let (phone, p) = person(book, id)?;
    let chosen = if p.numbers.len() == 1 { p.numbers.first() } else { p.numbers.iter().find(|n| n.preferred) };
    Some(match chosen {
        Some(n) => Pick::Call { phone: phone.to_string(), number: n.number.clone() },
        None => Pick::Ask { phone: phone.to_string(), name: p.name.clone() },
    })
}

// ---- With the app ------------------------------------------------------------------------------------------------------------

pub fn search(terms: &[String]) -> Vec<String> {
    find(&BOOK.lock().unwrap(), terms)
}

pub fn describe(ids: &[String]) -> Vec<(String, String, String)> {
    metas(&BOOK.lock().unwrap(), ids)
}

/// A person was picked in the search of the desktop.
pub fn activate(app: &AppHandle, id: &str) {
    let Some(choice) = pick(&BOOK.lock().unwrap(), id) else { return };
    match choice {
        Pick::Call { phone, number } => {
            let app = app.clone();
            tauri::async_runtime::spawn(async move {
                let name = events::device_name(&app, &phone);
                // What the Call button does, so the call card shows up the same way.
                match crate::calls::dial(app.state::<AppState>(), phone, number.clone()).await {
                    Ok(()) => events::toast(&app, "Tandem", &i18n::t2(&app, "call_started", &name, &number)),
                    Err(_) => events::toast(&app, "Tandem", &i18n::t1(&app, "call_unreachable", &name)),
                }
            });
        }
        Pick::Ask { phone, name } => {
            let ask = json!({ "id": phone, "name": name });
            *PENDING.lock().unwrap() = Some(ask.clone());
            crate::tray::show_main(app);
            let _ = app.emit("call-ask", ask);
        }
    }
}

/// The person for the Call box that a window made just now has not heard of yet.
#[tauri::command]
pub fn call_ask_pending() -> Option<Value> {
    PENDING.lock().unwrap().take()
}

/// The Call box took the person.
#[tauri::command]
pub fn call_ask_taken() {
    PENDING.lock().unwrap().take();
}

/// Called at the start, when the devices change and when the setting changes: the phones that are due are asked for their list,
/// the ones that are gone are forgotten, and the search of the desktop is offered or taken away.
pub fn refresh(app: &AppHandle) {
    // Only Linux has a search to put them in; elsewhere nothing is asked of the phones for it.
    let on = cfg!(target_os = "linux") && settings::get(app).contacts_search;
    let phones: Vec<String> = {
        let state = app.state::<AppState>();
        let data = state.data.lock().unwrap();
        data.devices
            .iter()
            .filter(|d| d["online"].as_bool() == Some(true) && d["caps"].as_array().is_some_and(|c| c.iter().any(|c| c == "sms.contacts")))
            .filter_map(|d| d["id"].as_str().map(str::to_string))
            .collect()
    };
    let known: Vec<String> = {
        let state = app.state::<AppState>();
        let data = state.data.lock().unwrap();
        data.devices.iter().filter_map(|d| d["id"].as_str().map(str::to_string)).collect()
    };
    {
        let mut book = BOOK.lock().unwrap();
        if on {
            book.retain(|(phone, _)| known.contains(phone));
        } else {
            book.clear();
        }
    }
    // The provider on the bus answers for the other kinds too, so whether it is there is decided with those.
    crate::desktopsearch::refresh(app);
    if !on {
        *ASKED.lock().unwrap() = None;
        return;
    }
    let due: Vec<String> = {
        let mut asked = ASKED.lock().unwrap();
        let asked = asked.get_or_insert_with(HashMap::new);
        let due: Vec<String> = phones
            .into_iter()
            .filter(|p| match asked.get(p) {
                None => true,
                Some(None) => false,
                Some(Some(at)) => at.elapsed() >= REFRESH_EVERY,
            })
            .collect();
        for p in &due {
            asked.insert(p.clone(), None);
        }
        due
    };
    for phone in due {
        let app = app.clone();
        tauri::async_runtime::spawn(async move { fetch(&app, phone).await });
    }
}

async fn fetch(app: &AppHandle, phone: String) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let result = engine.sms_all_contacts(phone.clone()).await;
    // A phone that did not answer is asked again sooner than one that did.
    let next = match &result {
        Ok(_) | Err(TandemSmsError::Off | TandemSmsError::NoPermission | TandemSmsError::Unavailable) => Instant::now(),
        Err(_) => Instant::now().checked_sub(REFRESH_EVERY - RETRY_AFTER).unwrap_or_else(Instant::now),
    };
    if let Some(asked) = ASKED.lock().unwrap().as_mut() {
        asked.insert(phone.clone(), Some(next));
    }
    let people = match result {
        Ok(people) => people,
        // The phone says no: its people go.
        Err(TandemSmsError::Off | TandemSmsError::NoPermission | TandemSmsError::Unavailable) => Vec::new(),
        Err(_) => return,
    };
    if !cfg!(target_os = "linux") || !settings::get(app).contacts_search {
        return;
    }
    let mut book = BOOK.lock().unwrap();
    book.retain(|(p, _)| *p != phone);
    if !people.is_empty() {
        book.push((phone, people));
    }
}

/// Every quarter of an hour the phones that are due are asked again; the devices changing does the rest.
pub fn start(app: AppHandle) {
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(Duration::from_secs(15 * 60));
            refresh(&app);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use tandem_core::ffi::sms::TandemPhoneNumber;

    fn number(number: &str, label: &str, preferred: bool) -> TandemPhoneNumber {
        TandemPhoneNumber { number: number.into(), label: label.into(), preferred }
    }

    fn book() -> Vec<(String, Vec<TandemPhoneContact>)> {
        vec![(
            "phone".into(),
            vec![
                TandemPhoneContact { id: 1, name: "Anna de Vries".into(), numbers: vec![number("+31 6 1234 5678", "Mobile", true)] },
                TandemPhoneContact {
                    id: 2,
                    name: "Hanna Jansen".into(),
                    numbers: vec![number("+31611111111", "Mobile", false), number("+31201111111", "Work", false)],
                },
                TandemPhoneContact { id: 3, name: "Bakker Anna".into(), numbers: vec![number("+31622222222", "", false), number("020", "", true)] },
            ],
        )]
    }

    fn terms(text: &str) -> Vec<String> {
        text.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn a_name_is_found_by_the_start_of_its_words_best_first() {
        let book = book();
        // "anna" is the start of a word in two names and inside "Hanna", which is not what was meant.
        assert_eq!(find(&book, &terms("anna")), vec!["phone/1", "phone/3"]);
        assert_eq!(find(&book, &terms("Anna Vr")), vec!["phone/1"]);
        assert_eq!(find(&book, &terms("jans")), vec!["phone/2"]);
        assert!(find(&book, &terms("zz")).is_empty());
    }

    #[test]
    fn a_number_is_found_by_its_digits() {
        let book = book();
        assert_eq!(find(&book, &terms("12345")), vec!["phone/1"]);
        assert_eq!(find(&book, &terms("+31201")), vec!["phone/2"]);
    }

    #[test]
    fn one_number_or_the_marked_one_is_called_otherwise_the_person_chooses() {
        let book = book();
        assert_eq!(pick(&book, "phone/1"), Some(Pick::Call { phone: "phone".into(), number: "+31 6 1234 5678".into() }));
        assert_eq!(pick(&book, "phone/2"), Some(Pick::Ask { phone: "phone".into(), name: "Hanna Jansen".into() }));
        assert_eq!(pick(&book, "phone/3"), Some(Pick::Call { phone: "phone".into(), number: "020".into() }));
        assert_eq!(pick(&book, "phone/9"), None);
        assert_eq!(pick(&book, "nonsense"), None);
    }

    #[test]
    fn the_file_for_gnome_points_at_the_provider_and_the_app() {
        // Kept in step by hand with `tandem_winsys::searchprovider` (only built on Linux) and the desktop file that the package
        // names after `mainBinaryName`.
        let ini = include_str!("../linux/nl.markmaaktmedia.tandem.search-provider.ini");
        assert!(ini.contains("BusName=nl.markmaaktmedia.tandem.SearchProvider\n"));
        assert!(ini.contains("ObjectPath=/nl/markmaaktmedia/tandem/SearchProvider\n"));
        let conf: Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let desktop = format!("DesktopId={}.desktop\n", conf["mainBinaryName"].as_str().unwrap());
        assert!(ini.contains(&desktop), "{desktop}");
        assert!(conf["bundle"]["linux"]["deb"]["files"].as_object().unwrap().values().any(|v| v == "linux/nl.markmaaktmedia.tandem.search-provider.ini"));
    }

    #[test]
    fn the_line_under_a_name_has_the_numbers_with_their_kind() {
        let book = book();
        let meta = metas(&book, &["phone/2".to_string(), "gone/7".to_string()]);
        assert_eq!(meta, vec![("phone/2".into(), "Hanna Jansen".into(), "Mobile +31611111111, Work +31201111111".into())]);
    }
}
