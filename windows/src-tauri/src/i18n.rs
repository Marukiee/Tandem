//! The few words the app itself says outside its windows: Windows notifications and the menu of the tray icon.
//! Everything inside the windows is translated by the interface.

use tauri::AppHandle;

use crate::settings;

pub fn lang(app: &AppHandle) -> &'static str {
    let chosen = settings::get(app).language;
    let tag = if chosen == "auto" { sys_locale::get_locale().unwrap_or_default() } else { chosen };
    if tag.to_lowercase().starts_with("nl") { "nl" } else { "en" }
}

fn text(lang: &str, key: &str) -> &'static str {
    match (lang, key) {
        ("nl", "paired_title") => "Apparaat toegevoegd",
        (_, "paired_title") => "Device added",
        ("nl", "paired_body") => "{0} is toegevoegd aan je kring",
        (_, "paired_body") => "{0} is now part of your circle",
        ("nl", "removed_from_circle") => "Deze pc is uit de kring gehaald",
        (_, "removed_from_circle") => "This PC was removed from the circle",
        ("nl", "text_on_clipboard") => "Tekst van {0} staat op je klembord",
        (_, "text_on_clipboard") => "Text from {0} is on your clipboard",
        ("nl", "link_from") => "Link van {0}",
        (_, "link_from") => "Link from {0}",
        ("nl", "text_from") => "Tekst van {0}",
        (_, "text_from") => "Text from {0}",
        ("nl", "wants_to_send") => "{0} wil {1} bestand(en) sturen",
        (_, "wants_to_send") => "{0} wants to send {1} file(s)",
        ("nl", "received") => "{0} ontvangen",
        (_, "received") => "Received {0}",
        ("nl", "received_from") => "Van {0}",
        (_, "received_from") => "From {0}",
        ("nl", "failed") => "Overdracht van {0} mislukt",
        (_, "failed") => "Could not transfer {0}",
        ("nl", "code_copied") => "Code {0} gekopieerd",
        (_, "code_copied") => "Code {0} copied",
        ("nl", "unknown_number") => "Onbekend nummer",
        (_, "unknown_number") => "Unknown number",
        ("nl", "calling") => "{0} gaat over",
        (_, "calling") => "{0} is ringing",
        ("nl", "missed_call") => "Gemiste oproep",
        (_, "missed_call") => "Missed call",
        ("nl", "open_tandem") => "Tandem openen",
        (_, "open_tandem") => "Open Tandem",
        ("nl", "quit_tandem") => "Tandem afsluiten",
        (_, "quit_tandem") => "Quit Tandem",
        _ => "",
    }
}

pub fn t(app: &AppHandle, key: &str) -> String {
    text(lang(app), key).to_string()
}

pub fn t1(app: &AppHandle, key: &str, a: &str) -> String {
    text(lang(app), key).replace("{0}", a)
}

pub fn t2(app: &AppHandle, key: &str, a: &str, b: &str) -> String {
    text(lang(app), key).replace("{0}", a).replace("{1}", b)
}
