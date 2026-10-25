//! Everything of Tandem in the search of the desktop (the GNOME overview and KRunner), next to the contacts of the phones
//! (`contactsearch.rs`): the devices, what can be done with each, the conversations of the phones, the clipboard history, the shared
//! files and the settings. Each kind has its own switch. The search asks while the person types, and the answer is made from what the
//! app has at that moment, so nothing goes stale; only the conversations are fetched from the phone ahead (they are not otherwise here
//! until the Messages page is opened). What is found, how it ranks and what a pick does is here, so it is tested on any system; the bus
//! is in `tandem_winsys::searchprovider`. Windows has no way for an app to put results in the search of the Start menu (docs/PARITY.md).
#![cfg_attr(not(target_os = "linux"), allow(dead_code))]

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tandem_core::ffi::sms::{TandemSmsConversation, TandemSmsError};
use tauri::{AppHandle, Emitter, Manager};

use crate::history::Item;
use crate::settings::Settings;
use crate::state::AppState;
use crate::{contactsearch, events, settings};

const REFRESH_EVERY: Duration = Duration::from_secs(3 * 3600);
const RETRY_AFTER: Duration = Duration::from_secs(10 * 60);
/// What the overview shows of one search at most; more is a list nobody reads.
const MAX_RESULTS: usize = 10;

/// The first page of conversations of each phone, newest first, and what the phone told after it.
static CONVERSATIONS: Mutex<Vec<(String, Vec<TandemSmsConversation>)>> = Mutex::new(Vec::new());
/// When each phone was last asked, or is being asked (`None` while the question runs).
static ASKED: Mutex<Option<HashMap<String, Option<Instant>>>> = Mutex::new(None);
/// Where the window should go, for a window that had to be made first.
static OPEN: Mutex<Option<Value>> = Mutex::new(None);

/// The switches, one per kind.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Kinds {
    pub devices: bool,
    pub actions: bool,
    pub conversations: bool,
    pub clipboard: bool,
    pub files: bool,
    pub settings: bool,
}

impl Kinds {
    pub fn of(s: &Settings) -> Kinds {
        Kinds {
            devices: s.search_devices,
            actions: s.search_actions,
            conversations: s.search_messages,
            clipboard: s.search_clipboard,
            files: s.search_files,
            settings: s.search_settings,
        }
    }

    fn any(&self) -> bool {
        self.devices || self.actions || self.conversations || self.clipboard || self.files || self.settings
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    Clipboard,
    Ring,
    Extend,
    Screen,
    Wifi,
    Files,
    Messages,
}

impl Action {
    const ALL: [Action; 7] = [Action::Clipboard, Action::Ring, Action::Extend, Action::Screen, Action::Wifi, Action::Files, Action::Messages];

    fn word(self) -> &'static str {
        match self {
            Action::Clipboard => "clipboard",
            Action::Ring => "ring",
            Action::Extend => "extend",
            Action::Screen => "screen",
            Action::Wifi => "wifi",
            Action::Files => "files",
            Action::Messages => "messages",
        }
    }
}

/// What a result stands for, written into its id. A contact has an id of its own form (`contactsearch`), which none of these is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Target {
    Device(String),
    Action(String, Action),
    Conversation(String, u64),
    Clip(u64),
    File(String),
    Setting(String, String),
}

impl Target {
    pub fn id(&self) -> String {
        match self {
            Target::Device(id) => format!("device/{id}"),
            Target::Action(id, action) => format!("action/{id}/{}", action.word()),
            Target::Conversation(id, thread) => format!("conversation/{id}/{thread}"),
            Target::Clip(id) => format!("clip/{id}"),
            Target::File(id) => format!("file/{id}"),
            Target::Setting(section, key) => format!("setting/{section}/{key}"),
        }
    }

    pub fn parse(id: &str) -> Option<Target> {
        let (kind, rest) = id.split_once('/')?;
        let pair = rest.split_once('/');
        Some(match kind {
            "device" if !rest.is_empty() && !rest.contains('/') => Target::Device(rest.to_string()),
            "action" => {
                let (device, word) = pair?;
                Target::Action(device.to_string(), Action::ALL.into_iter().find(|a| a.word() == word)?)
            }
            "conversation" => {
                let (device, thread) = pair?;
                Target::Conversation(device.to_string(), thread.parse().ok()?)
            }
            "clip" => Target::Clip(rest.parse().ok()?),
            "file" if !rest.is_empty() => Target::File(rest.to_string()),
            "setting" => {
                let (section, key) = pair?;
                if !SECTIONS.contains(&section) {
                    return None;
                }
                Target::Setting(section.to_string(), key.to_string())
            }
            _ => return None,
        })
    }
}

/// The sections of the settings page (`SECTIONS` in pages.js).
const SECTIONS: [&str; 9] = ["general", "features", "clipboard", "files", "remote", "mouse", "quickshare", "updates", "about"];

/// Which kind a result is, in the order results of the same quality are shown.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind {
    Device,
    Action,
    Contact,
    Setting,
    Conversation,
    File,
    Clip,
}

/// One result: how it is shown and the words it is found by.
#[derive(Clone, Debug, PartialEq)]
pub struct Entry {
    pub target: Target,
    pub kind: Kind,
    pub name: String,
    pub description: String,
    pub icon: &'static str,
    words: Vec<String>,
    /// Phone numbers, found by their digits.
    numbers: Vec<String>,
}

/// What the answer is made from.
pub struct Snapshot {
    pub lang: &'static str,
    pub devices: Vec<Value>,
    pub conversations: Vec<(String, Vec<TandemSmsConversation>)>,
    pub clips: Vec<Item>,
    pub transfers: Vec<Value>,
    pub can_extend: bool,
    pub wifi_here: Option<String>,
}

/// Lower case and without accents, so "kopieren" finds "kopiëren" and "e" finds "é".
fn fold(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' => 'a',
            'è' | 'é' | 'ê' | 'ë' => 'e',
            'ì' | 'í' | 'î' | 'ï' => 'i',
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' => 'o',
            'ù' | 'ú' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            'ñ' => 'n',
            other => other,
        })
        .collect()
}

fn words(text: &str) -> Vec<String> {
    fold(text).split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).map(str::to_string).collect()
}

fn digits(text: &str) -> String {
    text.chars().filter(char::is_ascii_digit).collect()
}

fn tr(lang: &str, key: &str) -> &'static str {
    let nl = lang == "nl";
    match key {
        "phone" => if nl { "Telefoon" } else { "Phone" },
        "computer" => if nl { "Computer" } else { "Computer" },
        "in_tandem" => if nl { "{0} in Tandem" } else { "{0} in Tandem" },
        "clipboard" => if nl { "Klembord naar {0} sturen" } else { "Send clipboard to {0}" },
        "clipboard_sub" => if nl { "Stuurt de tekst op het klembord van deze computer" } else { "Sends the text on the clipboard of this computer" },
        "find" => if nl { "{0} zoeken" } else { "Find {0}" },
        "find_sub" => if nl { "Laat de telefoon overgaan, ook als hij op stil staat" } else { "Rings the phone, even when it is silent" },
        "ring" => if nl { "{0} laten klinken" } else { "Ring {0}" },
        "ring_sub" => if nl { "Speelt er een geluid af, om hem te vinden" } else { "Plays a sound on it, to find it" },
        "extend" => if nl { "Scherm uitbreiden naar {0}" } else { "Extend screen to {0}" },
        "extend_sub" => if nl { "Deze computer krijgt een extra scherm, dat daar te zien is" } else { "This computer gets an extra screen, shown there" },
        "screen" => if nl { "Scherm van {0} tonen" } else { "Show {0}'s screen" },
        "screen_sub" => if nl { "Het scherm in een venster hier" } else { "Its screen in a window here" },
        "wifi" => if nl { "Wifi delen met {0}" } else { "Share Wi-Fi with {0}" },
        "wifi_sub" => if nl { "Stuurt de naam en het wachtwoord van de wifi van deze computer" } else { "Sends the name and password of this computer's Wi-Fi" },
        "files" => if nl { "Bestanden op {0} bekijken" } else { "Browse files on {0}" },
        "files_sub" => if nl { "De bestanden in Tandem" } else { "Its files in Tandem" },
        "messages" => if nl { "Berichten van {0}" } else { "Messages of {0}" },
        "messages_sub" => if nl { "De sms'jes van de telefoon" } else { "The text messages of the phone" },
        "copied_here" => if nl { "Hier gekopieerd" } else { "Copied here" },
        "copied_from" => if nl { "Van {0}" } else { "From {0}" },
        "received_from" => if nl { "Ontvangen van {0}" } else { "Received from {0}" },
        "sent_to" => if nl { "Gestuurd naar {0}" } else { "Sent to {0}" },
        "settings_page" => if nl { "Instellingen van Tandem: {0}" } else { "Tandem settings: {0}" },
        "settings_page_sub" => if nl { "Opent deze pagina van de instellingen" } else { "Opens this page of the settings" },
        "settings_in" => if nl { "Instellingen van Tandem, {0}" } else { "Tandem settings, {0}" },
        "clipboard_sent" => if nl { "Klembord verstuurd" } else { "Clipboard sent" },
        "no_text" => if nl { "Er staat geen tekst op het klembord" } else { "There is no text on the clipboard" },
        "copied" => if nl { "Gekopieerd" } else { "Copied" },
        "ringing" => if nl { "Je telefoon gaat over" } else { "Your phone is ringing" },
        "plays_sound" => if nl { "{0} speelt een geluid af" } else { "{0} plays a sound" },
        "extend_asked" => if nl { "{0} is gevraagd het scherm te tonen" } else { "{0} was asked to show the screen" },
        "offline" => if nl { "{0} is nu niet verbonden" } else { "{0} is not connected right now" },
        "gone" => if nl { "Dat is er niet meer" } else { "That is no longer there" },
        "general" => if nl { "Algemeen" } else { "General" },
        "features" => if nl { "Wat werkt" } else { "What works" },
        "clipboard_tab" => if nl { "Klembord" } else { "Clipboard" },
        "files_tab" => if nl { "Bestanden" } else { "Files" },
        "remote" => if nl { "Op afstand" } else { "Remote control" },
        "mouse" => if nl { "Muis en toetsenbord" } else { "Mouse and keyboard" },
        "quickshare" => "Quick Share",
        "updates" => "Updates",
        "about" => if nl { "Over" } else { "About" },
        "universal_mouse" => if nl { "Universele muis" } else { "Universal mouse" },
        "side_check" => if nl { "De kant vanzelf controleren" } else { "Check the side by itself" },
        "clip_history" => if nl { "Klembordgeschiedenis" } else { "Clipboard history" },
        "downloads" => if nl { "Map voor downloads" } else { "Downloads folder" },
        "autostart" => if nl { "Starten bij het inloggen" } else { "Start at login" },
        "language" => if nl { "Taal" } else { "Language" },
        "search" => if nl { "Zoeken van het bureaublad" } else { "Search of the desktop" },
        "tailscale" => if nl { "Tailscale aanzetten als dat nodig is" } else { "Turn on Tailscale when needed" },
        "bluetooth" => if nl { "Berichten via Bluetooth" } else { "Messages over Bluetooth" },
        "tray" => if nl { "Blijven draaien als het venster dicht is" } else { "Keep running when the window is closed" },
        "screen_host" => if nl { "Dit scherm aan andere apparaten laten zien" } else { "Show this screen to other devices" },
        "send_with" => if nl { "Sturen met Tandem" } else { "Send with Tandem" },
        "share_folders" => if nl { "Laat andere apparaten in mijn mappen kijken" } else { "Let other devices look at my folders" },
        "auto_update" => if nl { "Automatisch op updates controleren" } else { "Check for updates automatically" },
        _ => "",
    }
}

fn tr1(lang: &str, key: &str, value: &str) -> String {
    tr(lang, key).replace("{0}", value)
}

fn caps(device: &Value) -> Vec<&str> {
    device["caps"].as_array().map(|c| c.iter().filter_map(Value::as_str).collect()).unwrap_or_default()
}

fn is_computer(device: &Value) -> bool {
    matches!(device["platform"].as_str(), Some("macos" | "windows" | "linux"))
}

/// The same as `wifiWorthSharing` in pages.js: only for a device that is not on this Wi-Fi already.
fn wifi_worth(device: &Value, here: Option<&str>) -> bool {
    let Some(here) = here else { return false };
    let network = &device["status"]["network"];
    if network.is_null() {
        return true;
    }
    if let Some(ssid) = network["ssid"].as_str() {
        return ssid != here;
    }
    network["kind"].as_str() != Some("wifi")
}

fn entry(target: Target, kind: Kind, name: String, description: String, icon: &'static str, keywords: &[&str]) -> Entry {
    let mut found = words(&name);
    found.extend(keywords.iter().flat_map(|k| words(k)));
    Entry { target, kind, name, description, icon, words: found, numbers: Vec::new() }
}

fn device_entry(lang: &str, device: &Value) -> Option<Entry> {
    let id = device["id"].as_str()?;
    let name = device["name"].as_str().unwrap_or("?").to_string();
    let computer = is_computer(device);
    let kind = if computer { tr(lang, "computer") } else { tr(lang, "phone") };
    let keywords: &[&str] = if computer { &["computer", "pc", "tandem", "device", "apparaat"] } else { &["phone", "telefoon", "mobiel", "mobile", "tandem", "device", "apparaat"] };
    Some(entry(Target::Device(id.into()), Kind::Device, name, tr1(lang, "in_tandem", kind), if computer { "computer" } else { "phone" }, keywords))
}

/// Only what the page of the device offers it (pages.js, `DevicePage`): its caps, and what this computer can do.
fn action_entries(snapshot: &Snapshot, device: &Value) -> Vec<Entry> {
    let lang = snapshot.lang;
    let Some(id) = device["id"].as_str() else { return Vec::new() };
    let name = device["name"].as_str().unwrap_or("?");
    let caps = caps(device);
    let has = |cap: &str| caps.contains(&cap);
    let phone = device["platform"] == "android";
    let computer = is_computer(device);
    let mut list: Vec<(Action, &str, &str, &'static str, &[&str])> = vec![(
        Action::Clipboard,
        "clipboard",
        "clipboard_sub",
        "edit-paste",
        &["clipboard", "klembord", "send", "sturen", "versturen", "copy", "kopiëren", "paste", "plakken", "text", "tekst"],
    )];
    if phone {
        list.push((Action::Ring, "find", "find_sub", "audio-volume-high", &["find", "zoeken", "vinden", "ring", "bellen", "rinkelen", "sound", "geluid", "lost", "kwijt"]));
    } else if computer && has("ring") {
        list.push((Action::Ring, "ring", "ring_sub", "audio-volume-high", &["ring", "bellen", "find", "zoeken", "vinden", "sound", "geluid", "play", "afspelen", "lost", "kwijt"]));
    }
    if (computer || phone) && has("screen.extend.view") && snapshot.can_extend {
        list.push((Action::Extend, "extend", "extend_sub", "video-display", &["extend", "uitbreiden", "screen", "scherm", "display", "beeldscherm", "second", "tweede", "monitor"]));
    }
    if (computer || phone) && has("screen.host") {
        list.push((Action::Screen, "screen", "screen_sub", "video-display", &["show", "tonen", "laten", "zien", "screen", "scherm", "mirror", "spiegelen", "view", "bekijken", "control", "bedienen"]));
    }
    if has("wifi.join") && wifi_worth(device, snapshot.wifi_here.as_deref()) {
        list.push((Action::Wifi, "wifi", "wifi_sub", "network-wireless", &["wifi", "wi-fi", "wlan", "network", "netwerk", "password", "wachtwoord", "share", "delen"]));
    }
    if has("files") {
        list.push((Action::Files, "files", "files_sub", "folder", &["browse", "bladeren", "files", "bestanden", "folder", "map", "mappen", "bekijken"]));
    }
    if has("sms") {
        list.push((Action::Messages, "messages", "messages_sub", "mail-unread", &["messages", "berichten", "sms", "texts", "chat", "write", "schrijven"]));
    }
    list.into_iter()
        .map(|(action, title, sub, icon, keywords)| {
            let mut e = entry(Target::Action(id.into(), action), Kind::Action, tr1(lang, title, name), tr(lang, sub).to_string(), icon, keywords);
            e.words.push("tandem".into());
            e
        })
        .collect()
}

fn conversation_entry(lang: &str, phone: &str, phone_name: &str, c: &TandemSmsConversation) -> Entry {
    let who = if c.name.is_empty() { c.addresses.join(", ") } else { c.name.clone() };
    let description = if c.snippet.is_empty() { tr1(lang, "messages", phone_name) } else { c.snippet.clone() };
    let mut e = entry(Target::Conversation(phone.into(), c.thread), Kind::Conversation, who, description, "mail-unread", &["messages", "berichten", "sms"]);
    e.words.extend(words(&c.snippet));
    e.numbers = c.addresses.clone();
    e
}

fn clip_entry(lang: &str, item: &Item) -> Entry {
    let first = item.text.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("");
    let name: String = first.chars().take(120).collect();
    let description = if item.from.is_empty() { tr(lang, "copied_here").to_string() } else { tr1(lang, "copied_from", &item.from) };
    let mut e = entry(Target::Clip(item.id), Kind::Clip, name, description, "edit-copy", &["clipboard", "klembord", "copied", "gekopieerd"]);
    let text: String = item.text.chars().take(2000).collect();
    e.words.extend(words(&text));
    e
}

fn file_entry(lang: &str, transfer: &Value, devices: &[Value]) -> Option<Entry> {
    if transfer["state"] != "done" || transfer["location"].as_str().is_none_or(str::is_empty) {
        return None;
    }
    let id = transfer["id"].as_str()?;
    let name = transfer["name"].as_str().unwrap_or("?").to_string();
    let peer = devices.iter().find(|d| d["id"] == transfer["peer"]).and_then(|d| d["name"].as_str()).unwrap_or("?");
    let incoming = transfer["incoming"].as_bool() == Some(true);
    let description = tr1(lang, if incoming { "received_from" } else { "sent_to" }, peer);
    let keywords: &[&str] = if incoming { &["tandem", "received", "ontvangen", "file", "bestand"] } else { &["tandem", "sent", "verstuurd", "gestuurd", "file", "bestand"] };
    Some(entry(Target::File(id.into()), Kind::File, name, description, "text-x-generic", keywords))
}

/// The pages of the settings and the main switches, with words in both languages: someone with Tandem in English may still type Dutch.
fn setting_entries(lang: &str) -> Vec<Entry> {
    let page_title = |section: &str| tr(lang, if section == "clipboard" { "clipboard_tab" } else if section == "files" { "files_tab" } else { section });
    let pages: [(&str, &[&str]); 9] = [
        ("general", &["general", "algemeen", "name", "naam", "behaviour", "gedrag"]),
        ("features", &["features", "functies", "permissions", "rechten", "werkt"]),
        ("clipboard", &["clipboard", "klembord", "history", "geschiedenis"]),
        ("files", &["files", "bestanden", "share", "delen", "folders", "mappen"]),
        ("remote", &["remote", "afstand", "control", "bedienen", "screen", "scherm"]),
        ("mouse", &["mouse", "muis", "keyboard", "toetsenbord", "pointer", "aanwijzer"]),
        ("quickshare", &["quick", "share", "nearby", "delen", "android"]),
        ("updates", &["updates", "update", "bijwerken", "version", "versie"]),
        ("about", &["about", "over", "version", "versie"]),
    ];
    let switches: [(&str, &str, &str, &[&str]); 15] = [
        ("mouse", "universal-mouse", "universal_mouse", &["universal", "universele", "shared", "gedeelde", "mouse", "muis", "keyboard", "toetsenbord"]),
        ("mouse", "side-check", "side_check", &["side", "kant", "check", "controleren", "sound", "geluid", "automatic", "vanzelf"]),
        ("clipboard", "history", "clip_history", &["clipboard", "klembord", "history", "geschiedenis", "klembordgeschiedenis", "keep", "bewaren"]),
        ("general", "downloads", "downloads", &["downloads", "folder", "map", "received", "ontvangen", "files", "bestanden", "opslaan"]),
        ("general", "autostart", "autostart", &["start", "login", "inloggen", "opstarten", "automatic", "automatisch"]),
        ("general", "language", "language", &["language", "taal", "english", "engels", "nederlands", "dutch"]),
        ("general", "search", "search", &["search", "zoeken", "krunner", "gnome", "overview", "contacts", "contacten"]),
        ("general", "tailscale", "tailscale", &["tailscale", "vpn", "network", "netwerk"]),
        ("general", "bluetooth", "bluetooth", &["bluetooth", "network", "netwerk", "offline"]),
        ("general", "tray", "tray", &["tray", "background", "achtergrond", "close", "sluiten", "window", "venster"]),
        ("remote", "host", "screen_host", &["screen", "scherm", "share", "delen", "control", "bedienen", "remote", "afstand"]),
        ("files", "send-with", "send_with", &["send", "sturen", "file", "manager", "bestandsbeheer", "menu"]),
        ("files", "share", "share_folders", &["share", "delen", "files", "bestanden", "folders", "mappen", "browse", "bekijken"]),
        ("quickshare", "on", "quickshare", &["quick", "share", "nearby", "delen", "android"]),
        ("updates", "auto", "auto_update", &["updates", "bijwerken", "automatic", "automatisch", "check", "controleren"]),
    ];
    let settings: &[&str] = &["settings", "instellingen", "preferences", "voorkeuren", "tandem"];
    let mut list: Vec<Entry> = pages
        .iter()
        .map(|(section, keywords)| {
            let mut e = entry(
                Target::Setting(section.to_string(), "page".into()),
                Kind::Setting,
                tr1(lang, "settings_page", page_title(section)),
                tr(lang, "settings_page_sub").to_string(),
                "preferences-system",
                keywords,
            );
            e.words.extend(settings.iter().map(|s| s.to_string()));
            e
        })
        .collect();
    list.extend(switches.iter().map(|(section, key, title, keywords)| {
        let mut e = entry(
            Target::Setting(section.to_string(), key.to_string()),
            Kind::Setting,
            tr(lang, title).to_string(),
            tr1(lang, "settings_in", page_title(section)),
            "preferences-system",
            keywords,
        );
        e.words.extend(settings.iter().map(|s| s.to_string()));
        e
    }));
    list
}

/// Everything that can be found now, for the kinds that are on.
pub fn entries(snapshot: &Snapshot, kinds: Kinds) -> Vec<Entry> {
    let lang = snapshot.lang;
    let mut list = Vec::new();
    if kinds.devices {
        list.extend(snapshot.devices.iter().filter_map(|d| device_entry(lang, d)));
    }
    if kinds.actions {
        list.extend(snapshot.devices.iter().flat_map(|d| action_entries(snapshot, d)));
    }
    if kinds.conversations {
        for (phone, conversations) in &snapshot.conversations {
            let Some(device) = snapshot.devices.iter().find(|d| d["id"] == phone.as_str()) else { continue };
            if !caps(device).contains(&"sms") {
                continue;
            }
            let name = device["name"].as_str().unwrap_or("?");
            list.extend(conversations.iter().map(|c| conversation_entry(lang, phone, name, c)));
        }
    }
    if kinds.clipboard {
        list.extend(snapshot.clips.iter().take(200).map(|i| clip_entry(lang, i)));
    }
    if kinds.files {
        list.extend(snapshot.transfers.iter().filter_map(|t| file_entry(lang, t, &snapshot.devices)));
    }
    if kinds.settings {
        list.extend(setting_entries(lang));
    }
    list
}

/// What was typed, as the words it is matched by: "wi-fi" is "wi" and "fi", as the words of a result are.
fn split_terms(terms: &[String]) -> Vec<String> {
    terms.iter().flat_map(|t| words(t)).collect()
}

/// Every word that was typed is the start of a word of the result, or, for three digits or more, in one of its numbers.
fn matches(entry: &Entry, terms: &[String]) -> bool {
    terms.iter().all(|term| {
        if term.len() >= 3 && term.chars().all(|c| c.is_ascii_digit()) && entry.numbers.iter().any(|n| digits(n).contains(term.as_str())) {
            return true;
        }
        entry.words.iter().any(|w| w.starts_with(term.as_str()))
    })
}

/// How well a result fits: its name starts with what was typed, every word typed is in its name, or only in the words it is found by.
fn rank(name: &str, terms: &[String]) -> u8 {
    let name_words = words(name);
    if terms.first().is_some_and(|first| name_words.first().is_some_and(|w| w.starts_with(first.as_str()))) {
        0
    } else if terms.iter().all(|t| name_words.iter().any(|w| w.starts_with(t.as_str()))) {
        1
    } else {
        2
    }
}

/// The results of the kinds that are on and the contacts, best first. Within the same fit a device comes before what can be done with it,
/// and so on (`Kind`), and within a kind the order it came in, which is newest first for what has a date.
pub fn find(entries: &[Entry], contacts: &[(String, String)], terms: &[String]) -> Vec<String> {
    let terms = split_terms(terms);
    // One letter finds half of everything.
    if terms.iter().map(|t| t.chars().count()).sum::<usize>() < 2 {
        return Vec::new();
    }
    let mut hits: Vec<(u8, Kind, usize, String)> = entries
        .iter()
        .enumerate()
        .filter(|(_, e)| matches(e, &terms))
        .map(|(at, e)| (rank(&e.name, &terms), e.kind, at, e.target.id()))
        .collect();
    hits.extend(contacts.iter().enumerate().map(|(at, (id, name))| (rank(name, &terms), Kind::Contact, at, id.clone())));
    hits.sort();
    hits.into_iter().take(MAX_RESULTS).map(|(_, _, _, id)| id).collect()
}

// ---- With the app ------------------------------------------------------------------------------------------------------------

fn snapshot(app: &AppHandle) -> Snapshot {
    let state = app.state::<AppState>();
    let (devices, transfers) = {
        let data = state.data.lock().unwrap();
        (data.devices.clone(), data.transfers.clone())
    };
    Snapshot {
        lang: crate::i18n::lang(app),
        devices,
        conversations: CONVERSATIONS.lock().unwrap().clone(),
        clips: crate::history::items(app),
        transfers,
        can_extend: crate::commands::can_extend(),
        wifi_here: crate::wifi::wifi_here(),
    }
}

fn contacts_on(app: &AppHandle) -> bool {
    settings::get(app).contacts_search
}

pub fn search(app: &AppHandle, terms: &[String]) -> Vec<String> {
    let kinds = Kinds::of(&settings::get(app));
    let list = if kinds.any() { entries(&snapshot(app), kinds) } else { Vec::new() };
    let contacts: Vec<(String, String)> = if contacts_on(app) {
        let ids = contactsearch::search(terms);
        contactsearch::describe(&ids).into_iter().map(|(id, name, _)| (id, name)).collect()
    } else {
        Vec::new()
    };
    find(&list, &contacts, terms)
}

pub fn describe(app: &AppHandle, ids: &[String]) -> Vec<tandem_winsys_meta::Meta> {
    let kinds = Kinds::of(&settings::get(app));
    let list = entries(&snapshot(app), kinds);
    ids.iter()
        .filter_map(|id| match Target::parse(id) {
            Some(target) => list.iter().find(|e| e.target == target).map(|e| tandem_winsys_meta::Meta {
                id: id.clone(),
                name: e.name.clone(),
                description: e.description.clone(),
                icon: e.icon.to_string(),
            }),
            None => contactsearch::describe(std::slice::from_ref(id)).into_iter().next().map(|(id, name, description)| tandem_winsys_meta::Meta {
                id,
                name,
                description,
                icon: "call-start".into(),
            }),
        })
        .collect()
}

/// `Meta` of the provider, which is only built on Linux; the same fields elsewhere, so this file is tested on any system.
#[cfg(target_os = "linux")]
mod tandem_winsys_meta {
    pub use tandem_winsys::searchprovider::Meta;
}

#[cfg(not(target_os = "linux"))]
mod tandem_winsys_meta {
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Meta {
        pub id: String,
        pub name: String,
        pub description: String,
        pub icon: String,
    }
}

fn device(app: &AppHandle, id: &str) -> Option<Value> {
    app.state::<AppState>().data.lock().unwrap().devices.iter().find(|d| d["id"] == id).cloned()
}

/// The window goes to a page: shown at once when it is there, taken when it is made (`search_open_pending`).
fn navigate(app: &AppHandle, place: Value) {
    *OPEN.lock().unwrap() = Some(place.clone());
    crate::tray::show_main(app);
    let _ = app.emit("search-open", place);
}

/// Where a window made just now should go, once.
#[tauri::command]
pub fn search_open_pending() -> Option<Value> {
    OPEN.lock().unwrap().take()
}

fn say(app: &AppHandle, text: &str) {
    events::toast(app, "Tandem", text);
}

/// A result was picked in the search of the desktop. An action does what its button on the device page does.
pub fn activate(app: &AppHandle, id: &str) {
    let Some(target) = Target::parse(id) else {
        contactsearch::activate(app, id);
        return;
    };
    let lang = crate::i18n::lang(app);
    match target {
        Target::Device(device) => navigate(app, json!({ "page": "device", "selected": device })),
        Target::Conversation(device, thread) => navigate(app, json!({ "page": "messages", "selected": device, "thread": thread })),
        Target::Setting(section, key) => navigate(app, json!({ "page": "settings", "section": section, "key": key })),
        Target::Clip(item) => match crate::history::clip_history_copy(app.clone(), item) {
            Ok(()) => say(app, tr(lang, "copied")),
            Err(_) => say(app, tr(lang, "gone")),
        },
        Target::File(transfer) => {
            let location = app.state::<AppState>().data.lock().unwrap().transfers.iter().find(|t| t["id"] == transfer.as_str()).and_then(|t| t["location"].as_str().map(str::to_string));
            match location.filter(|l| std::path::Path::new(l).exists()) {
                Some(path) => {
                    use tauri_plugin_opener::OpenerExt;
                    if app.opener().open_path(path.clone(), None::<&str>).is_err() {
                        let _ = app.opener().reveal_item_in_dir(path);
                    }
                }
                None => say(app, tr(lang, "gone")),
            }
        }
        Target::Action(device_id, action) => {
            let Some(found) = device(app, &device_id) else {
                say(app, tr(lang, "gone"));
                return;
            };
            run(app, found, action);
        }
    }
}

fn run(app: &AppHandle, device: Value, action: Action) {
    let lang = crate::i18n::lang(app);
    let id = device["id"].as_str().unwrap_or_default().to_string();
    let name = device["name"].as_str().unwrap_or("?").to_string();
    match action {
        Action::Files => return navigate(app, json!({ "page": "files", "selected": id })),
        Action::Messages => return navigate(app, json!({ "page": "messages", "selected": id })),
        _ => {}
    }
    let online = device["online"].as_bool() == Some(true);
    let ble = device["ble"].as_bool() == Some(true);
    // Clipboard and Wi-Fi are small enough for Bluetooth, as their buttons are.
    if !(online || (ble && matches!(action, Action::Clipboard | Action::Wifi))) {
        return say(app, &tr1(lang, "offline", &name));
    }
    let app = app.clone();
    let computer = is_computer(&device);
    let mac = device["platform"] == "macos";
    tauri::async_runtime::spawn(async move {
        let result: Result<String, String> = match action {
            Action::Clipboard => crate::commands::send_clipboard(app.state::<AppState>(), vec![id])
                .await
                .map(|_| tr(lang, "clipboard_sent").to_string())
                .map_err(|e| if e == "no-text" { tr(lang, "no_text").to_string() } else { e }),
            Action::Ring => crate::commands::ring(app.state::<AppState>(), id, true)
                .await
                .map(|()| if computer { tr1(lang, "plays_sound", &name) } else { tr(lang, "ringing").to_string() }),
            Action::Extend => crate::live::display_offer(app.clone(), id).map(|()| tr1(lang, "extend_asked", &name)),
            Action::Screen => crate::live::live_start(app.clone(), id, "screen".into(), name.clone(), Some(computer), Some(mac)).await.map(|_| String::new()),
            Action::Wifi => crate::wifi::wifi_share(app.clone(), id).await,
            Action::Files | Action::Messages => Ok(String::new()),
        };
        match result {
            Ok(text) if !text.is_empty() => say(&app, &text),
            Ok(_) => {}
            Err(error) => say(&app, &error),
        }
    });
}

// ---- Conversations -----------------------------------------------------------------------------------------------------------

/// What a phone tells by itself about conversations that are new or changed.
pub fn conversations_changed(app: &AppHandle, phone: &str, changes: &[TandemSmsConversation]) {
    if changes.is_empty() || !settings::get(app).search_messages || device(app, phone).is_none() {
        return;
    }
    let mut all = CONVERSATIONS.lock().unwrap();
    let list = match all.iter_mut().find(|(p, _)| p == phone) {
        Some((_, list)) => list,
        None => {
            all.push((phone.to_string(), Vec::new()));
            &mut all.last_mut().unwrap().1
        }
    };
    *list = merged(list, changes);
}

/// `changes` in place of what had the same conversation, newest first, at most 60.
pub fn merged(list: &[TandemSmsConversation], changes: &[TandemSmsConversation]) -> Vec<TandemSmsConversation> {
    let mut out: Vec<TandemSmsConversation> = list.iter().filter(|c| !changes.iter().any(|n| n.thread == c.thread)).cloned().collect();
    out.extend(changes.iter().cloned());
    out.sort_by(|a, b| b.date.cmp(&a.date).then(b.thread.cmp(&a.thread)));
    out.truncate(60);
    out
}

/// Called with the refresh of the contacts (start, devices that change, a setting that changes, every quarter of an hour): the provider is
/// offered while any kind is on, the conversations of phones that are gone are forgotten, and the phones that are due are asked.
pub fn refresh(app: &AppHandle) {
    let current = settings::get(app);
    let on = cfg!(target_os = "linux") && (current.contacts_search || Kinds::of(&current).any());
    #[cfg(target_os = "linux")]
    linux::configure(app, on);
    let devices = app.state::<AppState>().data.lock().unwrap().devices.clone();
    {
        let mut all = CONVERSATIONS.lock().unwrap();
        if on && current.search_messages {
            all.retain(|(phone, _)| devices.iter().any(|d| d["id"] == phone.as_str()));
        } else {
            all.clear();
        }
    }
    if !on || !current.search_messages {
        *ASKED.lock().unwrap() = None;
        return;
    }
    let phones: Vec<String> = devices
        .iter()
        .filter(|d| d["online"].as_bool() == Some(true) && caps(d).contains(&"sms"))
        .filter_map(|d| d["id"].as_str().map(str::to_string))
        .collect();
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
    let result = engine.sms_conversations(phone.clone(), 0, 40).await;
    // A phone that did not answer is asked again sooner than one that did.
    let next = match &result {
        Ok(_) | Err(TandemSmsError::Off | TandemSmsError::NoPermission | TandemSmsError::Unavailable) => Instant::now(),
        Err(_) => Instant::now().checked_sub(REFRESH_EVERY - RETRY_AFTER).unwrap_or_else(Instant::now),
    };
    if let Some(asked) = ASKED.lock().unwrap().as_mut() {
        asked.insert(phone.clone(), Some(next));
    }
    let list = match result {
        Ok(page) => page.items,
        // The phone says no: its conversations go.
        Err(TandemSmsError::Off | TandemSmsError::NoPermission | TandemSmsError::Unavailable) => Vec::new(),
        Err(_) => return,
    };
    if !settings::get(app).search_messages {
        return;
    }
    let mut all = CONVERSATIONS.lock().unwrap();
    all.retain(|(p, _)| *p != phone);
    if !list.is_empty() {
        all.push((phone, merged(&[], &list)));
    }
}

#[cfg(target_os = "linux")]
mod linux {
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};

    use tandem_winsys::searchprovider::{Meta, SearchProvider, Source};
    use tauri::AppHandle;

    static PROVIDER: Mutex<Option<SearchProvider>> = Mutex::new(None);

    struct App(AppHandle);

    impl Source for App {
        fn search(&self, terms: &[String]) -> Vec<String> {
            super::search(&self.0, terms)
        }
        fn metas(&self, ids: &[String]) -> Vec<Meta> {
            super::describe(&self.0, ids)
        }
        fn activate(&self, id: &str) {
            super::activate(&self.0, id);
        }
        fn launch(&self) {
            crate::tray::show_main(&self.0);
        }
    }

    /// Where KRunner looks for runners of other apps that answer on the bus.
    fn krunner_file() -> Option<PathBuf> {
        let data = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| Path::new(&h).join(".local/share")))?;
        Some(data.join("krunner/dbusplugins/tandem-contacts.desktop"))
    }

    pub fn configure(app: &AppHandle, on: bool) {
        let mut provider = PROVIDER.lock().unwrap();
        if on && provider.is_none() {
            *provider = SearchProvider::start(Arc::new(App(app.clone())));
        } else if !on {
            // Dropping the connection gives the name on the bus back: GNOME and KRunner then find nobody to ask.
            *provider = None;
        }
        if let Some(file) = krunner_file() {
            if on {
                crate::sendwith::put(&file, tandem_winsys::searchprovider::KRUNNER_DESKTOP, false);
            } else {
                let _ = std::fs::remove_file(file);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn phone() -> Value {
        json!({ "id": "p1", "name": "Pixel 9", "platform": "android", "online": true, "caps": ["sms", "files", "wifi.join", "screen.host", "screen.extend.view"],
                "status": { "network": { "kind": "wifi", "ssid": "Thuis" } } })
    }

    fn mac() -> Value {
        json!({ "id": "m1", "name": "MacBook", "platform": "macos", "online": true, "caps": ["ring", "files"], "status": {} })
    }

    fn old_laptop() -> Value {
        json!({ "id": "l1", "name": "Old laptop", "platform": "linux", "online": false, "caps": [], "status": {} })
    }

    fn conversation(thread: u64, name: &str, snippet: &str, date: u64) -> TandemSmsConversation {
        TandemSmsConversation { thread, addresses: vec!["+31 6 1234 5678".into()], name: name.into(), snippet: snippet.into(), date, unread: 0, from_me: false }
    }

    fn snapshot(lang: &'static str) -> Snapshot {
        Snapshot {
            lang,
            devices: vec![phone(), mac(), old_laptop()],
            conversations: vec![("p1".into(), vec![conversation(4, "Anna de Vries", "Zie je morgen bij het station", 20)])],
            clips: vec![Item { id: 9, text: "https://example.com/report\nsecond line".into(), from: "Pixel 9".into(), at_ms: 1, pinned: false }],
            transfers: vec![
                json!({ "id": "5-0-in", "peer": "p1", "name": "vakantie.jpg", "incoming": true, "state": "done", "location": "/tmp/vakantie.jpg" }),
                json!({ "id": "6-0-in", "peer": "p1", "name": "half.zip", "incoming": true, "state": "failed", "location": null }),
            ],
            can_extend: true,
            wifi_here: Some("Kantoor".into()),
        }
    }

    const ALL: Kinds = Kinds { devices: true, actions: true, conversations: true, clipboard: true, files: true, settings: true };

    fn terms(text: &str) -> Vec<String> {
        text.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn every_id_reads_back_as_what_it_stands_for() {
        let targets = [
            Target::Device("abc".into()),
            Target::Conversation("abc".into(), 42),
            Target::Clip(7),
            Target::File("5-0-in".into()),
            Target::Setting("mouse".into(), "universal-mouse".into()),
        ];
        for target in targets.into_iter().chain(Action::ALL.into_iter().map(|a| Target::Action("abc".into(), a))) {
            assert_eq!(Target::parse(&target.id()), Some(target.clone()), "{}", target.id());
        }
        // The ids of contacts are not ours, and nonsense is nothing.
        assert_eq!(Target::parse("abc123/7"), None);
        assert_eq!(Target::parse("action/abc/fly"), None);
        assert_eq!(Target::parse("setting/nowhere/x"), None);
        assert_eq!(Target::parse("conversation/abc/x"), None);
        assert_eq!(Target::parse("device/"), None);
    }

    #[test]
    fn a_device_only_has_the_actions_its_page_offers() {
        let s = snapshot("en");
        let actions = |device: &Value| action_entries(&s, device).into_iter().map(|e| e.target).collect::<Vec<_>>();
        use Action::*;
        let on = |id: &str, list: &[Action]| list.iter().map(|a| Target::Action(id.into(), *a)).collect::<Vec<_>>();
        assert_eq!(actions(&phone()), on("p1", &[Clipboard, Ring, Extend, Screen, Wifi, Files, Messages]));
        assert_eq!(actions(&mac()), on("m1", &[Clipboard, Ring, Files]));
        // A computer whose Tandem cannot ring has no Ring, and nothing of what it does not say it can.
        assert_eq!(actions(&old_laptop()), on("l1", &[Clipboard]));
        // Not on this computer's Wi-Fi only when it is on another one, and no extending when this computer cannot.
        let mut same = snapshot("en");
        same.wifi_here = Some("Thuis".into());
        same.can_extend = false;
        let list: Vec<Target> = action_entries(&same, &phone()).into_iter().map(|e| e.target).collect();
        assert!(!list.contains(&Target::Action("p1".into(), Wifi)));
        assert!(!list.contains(&Target::Action("p1".into(), Extend)));
    }

    #[test]
    fn actions_are_found_by_the_words_a_person_types_in_either_language() {
        let list = entries(&snapshot("en"), ALL);
        assert_eq!(find(&list, &[], &terms("send clip")), vec!["action/p1/clipboard", "action/m1/clipboard", "action/l1/clipboard"]);
        assert_eq!(find(&list, &[], &terms("klembord pixel"))[0], "action/p1/clipboard");
        assert_eq!(find(&list, &[], &terms("bellen mac")), vec!["action/m1/ring"]);
        assert_eq!(find(&list, &[], &terms("find pixel")), vec!["action/p1/ring"]);
        assert_eq!(find(&list, &[], &terms("wi-fi"))[0], "action/p1/wifi");
        assert!(find(&list, &[], &terms("scherm uitbreiden")).contains(&"action/p1/extend".to_string()));
        // Without the accent too.
        assert!(find(&list, &[], &terms("kopieren")).contains(&"action/p1/clipboard".to_string()));
    }

    #[test]
    fn the_name_of_a_device_finds_the_device_first() {
        let list = entries(&snapshot("en"), ALL);
        let found = find(&list, &[], &terms("pixel"));
        assert_eq!(found[0], "device/p1");
        assert!(found.iter().all(|id| id.contains("p1") || id.starts_with("clip/")), "{found:?}");
    }

    #[test]
    fn a_conversation_is_found_by_its_name_its_last_message_and_its_number() {
        let list = entries(&snapshot("en"), ALL);
        assert_eq!(find(&list, &[], &terms("anna")), vec!["conversation/p1/4"]);
        assert_eq!(find(&list, &[], &terms("station")), vec!["conversation/p1/4"]);
        assert_eq!(find(&list, &[], &terms("12345")), vec!["conversation/p1/4"]);
        let e = list.iter().find(|e| e.target == Target::Conversation("p1".into(), 4)).unwrap();
        assert_eq!((e.name.as_str(), e.description.as_str()), ("Anna de Vries", "Zie je morgen bij het station"));
    }

    #[test]
    fn a_contact_with_the_same_name_comes_after_the_device_and_before_the_conversation() {
        let list = entries(&snapshot("en"), ALL);
        let contacts = vec![("p1/3".to_string(), "Anna de Vries".to_string())];
        assert_eq!(find(&list, &contacts, &terms("anna")), vec!["p1/3", "conversation/p1/4"]);
    }

    #[test]
    fn clipboard_files_and_settings_are_found() {
        let list = entries(&snapshot("en"), ALL);
        assert_eq!(find(&list, &[], &terms("example report")), vec!["clip/9"]);
        assert_eq!(find(&list, &[], &terms("vakantie")), vec!["file/5-0-in"]);
        // A transfer that failed has no file to open.
        assert!(find(&list, &[], &terms("half")).is_empty());
        assert_eq!(find(&list, &[], &terms("universal mouse"))[0], "setting/mouse/universal-mouse");
        assert_eq!(find(&list, &[], &terms("universele muis"))[0], "setting/mouse/universal-mouse");
        assert_eq!(find(&list, &[], &terms("downloads"))[0], "setting/general/downloads");
        assert!(find(&list, &[], &terms("instellingen")).len() == MAX_RESULTS);
    }

    #[test]
    fn a_kind_that_is_off_finds_nothing_of_its_own() {
        let s = snapshot("en");
        let off = Kinds { conversations: false, clipboard: false, ..ALL };
        let list = entries(&s, off);
        assert!(find(&list, &[], &terms("station")).is_empty());
        assert!(find(&list, &[], &terms("example")).is_empty());
        assert!(entries(&s, Kinds::default()).is_empty());
        // A phone that is gone takes its conversations with it.
        let mut gone = snapshot("en");
        gone.devices.retain(|d| d["id"] != "p1");
        assert!(entries(&gone, ALL).iter().all(|e| !e.target.id().contains("p1")));
    }

    #[test]
    fn the_names_follow_the_language() {
        let list = entries(&snapshot("nl"), ALL);
        let name = |id: &str| list.iter().find(|e| e.target.id() == id).map(|e| e.name.clone()).unwrap();
        assert_eq!(name("action/p1/clipboard"), "Klembord naar Pixel 9 sturen");
        assert_eq!(name("action/m1/ring"), "MacBook laten klinken");
        assert_eq!(name("setting/mouse/universal-mouse"), "Universele muis");
        // English words still find it.
        assert_eq!(find(&list, &[], &terms("send clipboard pixel"))[0], "action/p1/clipboard");
    }

    #[test]
    fn one_letter_finds_nothing() {
        let list = entries(&snapshot("en"), ALL);
        assert!(find(&list, &[], &terms("p")).is_empty());
    }

    #[test]
    fn what_a_phone_tells_replaces_its_conversation_and_the_newest_comes_first() {
        let list = vec![conversation(1, "A", "old", 10), conversation(2, "B", "b", 5)];
        let after = merged(&list, &[conversation(2, "B", "new", 30)]);
        assert_eq!(after.iter().map(|c| (c.thread, c.snippet.as_str())).collect::<Vec<_>>(), vec![(2, "new"), (1, "old")]);
    }

    #[test]
    fn every_entry_has_a_name_an_icon_and_a_line_under_it() {
        for e in entries(&snapshot("en"), ALL) {
            assert!(!e.name.is_empty() && !e.icon.is_empty() && !e.description.is_empty(), "{e:?}");
        }
    }
}
