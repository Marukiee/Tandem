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
        ("nl", "remote_off_title") => "{0} wil deze pc bedienen",
        (_, "remote_off_title") => "{0} wants to control this PC",
        ("nl", "remote_off_body") => "Zet 'Telefoon mag deze pc bedienen' aan bij Instellingen in Tandem.",
        (_, "remote_off_body") => "Turn on 'Let my phone control this PC' under Settings in Tandem.",
        ("nl", "remote_blocked_body") => "Dit kan niet onder Wayland. Log in met een X11-sessie om je telefoon deze computer te laten bedienen.",
        (_, "remote_blocked_body") => "This does not work under Wayland. Log in with an X11 session to let your phone control this computer.",
        ("nl", "insert_waiting") => "Wacht op {0}. Maak de foto of scan op de telefoon.",
        (_, "insert_waiting") => "Waiting for {0}. Take the picture or scan on the phone.",
        ("nl", "insert_done") => "Het beeld van {0} staat op je klembord. Plak het waar je wilt.",
        (_, "insert_done") => "The picture from {0} is on your clipboard. Paste it where you want it.",
        ("nl", "insert_saved") => "Van {0} is een bestand binnengekomen en opgeslagen: {1}",
        (_, "insert_saved") => "A file came in from {0} and was saved: {1}",
        ("nl", "insert_failed") => "Er is niets aangekomen van {0}.",
        (_, "insert_failed") => "Nothing came in from {0}.",
        ("nl", "insert_cancelled") => "{0} heeft niets gemaakt.",
        (_, "insert_cancelled") => "{0} did not make anything.",
        ("nl", "insert_refused") => "{0} mag de camera niet gebruiken. Sta het toe in de instellingen van de telefoon.",
        (_, "insert_refused") => "{0} is not allowed to use the camera. Allow it in the settings of the phone.",
        ("nl", "insert_unavailable") => "{0} kan dit nu niet.",
        (_, "insert_unavailable") => "{0} cannot do this right now.",
        ("nl", "host_ask_title") => "{0} wil je scherm zien",
        (_, "host_ask_title") => "{0} wants to see your screen",
        ("nl", "host_ask_view") => "Het ziet alles op je scherm tot je het stopt. Je kunt het stoppen in het venster van Tandem.",
        (_, "host_ask_view") => "It sees everything on your screen until you stop it. You can stop it in the window of Tandem.",
        ("nl", "host_ask_control") => "Het ziet alles op je scherm en kan je muis en toetsenbord gebruiken, tot je het stopt. Je kunt het stoppen in het venster van Tandem.",
        (_, "host_ask_control") => "It sees everything on your screen and can use your mouse and keyboard until you stop it. You can stop it in the window of Tandem.",
        ("nl", "host_allow") => "Toestaan",
        (_, "host_allow") => "Allow",
        ("nl", "ts_on_title") => "Tailscale staat aan gezet",
        (_, "ts_on_title") => "Tailscale was turned on",
        ("nl", "ts_on_body") => "{0} was niet bereikbaar op dit netwerk, dus Tandem heeft Tailscale aangezet.",
        (_, "ts_on_body") => "{0} could not be reached on this network, so Tandem turned Tailscale on.",
        ("nl", "ts_login_title") => "Log in bij Tailscale",
        (_, "ts_login_title") => "Sign in to Tailscale",
        ("nl", "ts_login_body") => "Om {0} te bereiken moet je eerst inloggen bij Tailscale.",
        (_, "ts_login_body") => "To reach {0} you first have to sign in to Tailscale.",
        ("nl", "ts_perm_title") => "Tandem mag Tailscale niet aanzetten",
        (_, "ts_perm_title") => "Tandem may not turn Tailscale on",
        ("nl", "ts_perm_body") => "Draai eenmalig: sudo tailscale set --operator=$USER",
        (_, "ts_perm_body") => "Run once: sudo tailscale set --operator=$USER",
        ("nl", "host_always") => "Altijd toestaan",
        (_, "host_always") => "Always allow",
        ("nl", "host_deny") => "Weigeren",
        (_, "host_deny") => "Deny",
        ("nl", "host_started_title") => "Je scherm wordt getoond",
        (_, "host_started_title") => "Your screen is being shown",
        ("nl", "host_started_body") => "{0} kijkt naar je scherm. Stop het in het venster van Tandem.",
        (_, "host_started_body") => "{0} is looking at your screen. Stop it in the window of Tandem.",
        ("nl", "qs_what_text") => "Een link of tekst",
        (_, "qs_what_text") => "A link or text",
        ("nl", "qs_what_files") => "{0} bestanden",
        (_, "qs_what_files") => "{0} files",
        ("nl", "qs_accept") => "Accepteren",
        (_, "qs_accept") => "Accept",
        ("nl", "qs_decline") => "Weigeren",
        (_, "qs_decline") => "Decline",
        ("nl", "qs_failed_early") => "Een overdracht liep fout voordat er iets werd aangeboden ({0}).",
        (_, "qs_failed_early") => "A transfer failed before anything was offered ({0}).",
        ("nl", "input_stuck") => "Een ander apparaat wil de muis hier gebruiken, maar het bureaublad staat dat niet toe. Geef Tandem toestemming in de vraag die het bureaublad dan laat zien.",
        (_, "input_stuck") => "Another device wants to use the mouse here, but the desktop does not allow it. Allow Tandem in the question the desktop then shows.",
        ("nl", "live_end_declined") => "{0} staat het niet toe. Laat het daar toe in Tandem.",
        (_, "live_end_declined") => "{0} did not allow it. Allow it there, in Tandem.",
        ("nl", "live_end_policy") => "{0} staat dit op nooit. Verander dat in Tandem op dat apparaat.",
        (_, "live_end_policy") => "{0} is set to never allow this. Change that in Tandem on that device.",
        ("nl", "live_end_unavailable") => "{0} kan dit nu niet. Er ontbreekt een toestemming: op een Mac Schermopname en Toegankelijkheid voor Tandem, op een telefoon de bediening van Tandem onder Toegankelijkheid, op Linux het venster van het bureaublad.",
        (_, "live_end_unavailable") => "{0} cannot do this right now, a permission is missing there: Screen Recording and Accessibility for Tandem on a Mac, Tandem's control under Accessibility on a phone, the question of the desktop on Linux.",
        ("nl", "live_end_unsupported") => "Tandem op {0} kan dit nog niet. Werk Tandem daar bij.",
        (_, "live_end_unsupported") => "Tandem on {0} cannot do this yet. Update Tandem there.",
        ("nl", "live_end_timeout") => "{0} antwoordde niet. Staat Tandem daar open, en heb je de vraag beantwoord?",
        (_, "live_end_timeout") => "{0} did not answer. Is Tandem open there, and did you answer its question?",
        ("nl", "live_end_busy") => "{0} is al met een ander scherm bezig.",
        (_, "live_end_busy") => "{0} is already busy with another screen.",
        ("nl", "live_end_lost") => "De verbinding met {0} is weg.",
        (_, "live_end_lost") => "The connection to {0} was lost.",
        ("nl", "open_tandem") => "Tandem openen",
        (_, "open_tandem") => "Open Tandem",
        ("nl", "quick_share") => "Quick Share",
        (_, "quick_share") => "Quick Share",
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
