// The words of the interface, in English and Dutch. Windows picks which one, unless the person chose.
const en = {
  files_offered: "Files offered",
  devices: "Devices", shared: "Shared", notifications: "Notifications", settings: "Settings", add_device: "Add a device",
  connected: "Connected", not_connected: "Not connected", reconnecting: "Tandem connects again as soon as this device can be reached.",
  send_files: "Send files", send_clipboard: "Send clipboard", find_phone: "Find phone", stop_ringing: "Stop ringing",
  drop_here: "Drop files here", drop_hint: "or use Send files", sent_files: "Offered {0} file(s)", offline_n: "{0} device(s) were offline and did not get it",
  not_connected_now: "That device is not connected right now", no_text: "There is no text on the clipboard", clipboard_sent: "Clipboard sent",
  folders_skipped: "Folders cannot be sent yet", recent: "Recent", nothing_yet: "Nothing yet",
  sync_clipboard: "Sync clipboard", sync_clipboard_sub: "Text and links you copy show up on both devices",
  show_its_notifications: "Show its notifications", show_its_notifications_sub: "Phone notifications appear here and as Windows notifications",
  accept_automatically: "Accept files automatically", accept_automatically_sub: "Turn off to be asked before something arrives",
  remove_device: "Remove this device", remove_device_sub: "It leaves the circle everywhere", remove: "Remove",
  remove_confirm: "Remove {0}? It leaves the circle on every device and cannot come back without a new pairing.", cancel: "Cancel",
  now_playing: "Now playing", previous: "Previous", next: "Next", play: "Play", pause: "Pause",
  wants_to_send: "{0} wants to send {1} file(s)", accept: "Accept", decline: "Decline",
  local_network: "Local network", tailscale: "Tailscale", internet: "Internet",
  on_wifi: "On Wi-Fi", on_wifi_ssid: "On {0}", on_cellular: "On mobile data", on_cellular_roaming: "On mobile data (roaming)", on_ethernet: "On Ethernet", no_network: "No network",
  hotspot_on: "Hotspot on", hotspot_cellular: "Hotspot on, sharing mobile data", dnd: "Do not disturb", charging: "Charging", battery: "Battery",
  add_title: "Add a device", add_lead: "Open Tandem on your phone, choose Add device and scan this code. It works for five minutes.",
  show_code: "Show a code", enter_code: "Enter a code", copy_link: "Copy link", copied: "Copied", paste_label: "Paste the link of the other device",
  pair: "Pair", pairing: "Pairing…", paired_with: "Paired with {0}", pair_failed: "Pairing did not work", new_code: "New code", close: "Close",
  welcome_title: "Pair your phone", welcome_lead: "Tandem connects this PC with your phone and Mac over your own network, with nothing in between.",
  step1: "Install Tandem on your phone", step2: "Open it and choose Add device", step3: "Scan the code on this screen",
  firewall: "If Windows asks about the firewall, choose Allow on private networks. Without it your phone cannot find this PC.",
  title_shared: "Shared", lead_shared: "Files that went to and came from your devices", incoming: "Received", outgoing: "Sent", open_folder: "Open folder", show_in_folder: "Show in folder",
  failed: "Failed", active: "Active", done: "Done",
  title_notifications: "Notifications", lead_notifications: "What your phones showed. New ones also appear as Windows notifications.",
  no_notifications: "No notifications yet", no_notifications_sub: "When a phone shows a notification, it is listed here.", clear_all: "Clear all",
  this_pc: "This PC", pc_name: "Name", save: "Save", download_folder: "Received files go to", change: "Change",
  start_with_windows: "Start with Windows", start_with_windows_sub: "Tandem waits in the tray so your phone can always reach this PC",
  close_to_tray: "Keep running when the window is closed", close_to_tray_sub: "The close button hides Tandem in the tray",
  copy_codes: "Copy codes from text messages", copy_codes_sub: "A verification code from your phone goes straight to the clipboard",
  phone_notifications: "Show phone notifications as Windows notifications", phone_notifications_sub: "Replies are not possible from here yet",
  language: "Language", language_auto: "Same as Windows", about: "About", version: "Version", engine_starting: "Starting…", engine_failed: "Tandem could not start",
  panel_open: "Open Tandem", panel_none: "No devices yet", panel_connected: "{0} of {1} connected", pc_connected_summary: "{0} connected",
  mac: "Mac", phone: "Phone", pc: "PC", unknown: "Device", quit: "Quit",
};

const nl = {
  files_offered: "Bestanden aangeboden",
  devices: "Apparaten", shared: "Gedeeld", notifications: "Meldingen", settings: "Instellingen", add_device: "Apparaat toevoegen",
  connected: "Verbonden", not_connected: "Niet verbonden", reconnecting: "Tandem verbindt weer zodra dit apparaat bereikbaar is.",
  send_files: "Bestanden versturen", send_clipboard: "Klembord versturen", find_phone: "Telefoon zoeken", stop_ringing: "Stop met bellen",
  drop_here: "Sleep bestanden hierheen", drop_hint: "of gebruik Bestanden versturen", sent_files: "{0} bestand(en) aangeboden", offline_n: "{0} apparaat/apparaten waren offline en kregen het niet",
  not_connected_now: "Dat apparaat is nu niet verbonden", no_text: "Er staat geen tekst op het klembord", clipboard_sent: "Klembord verstuurd",
  folders_skipped: "Mappen kunnen nog niet worden verstuurd", recent: "Recent", nothing_yet: "Nog niets",
  sync_clipboard: "Klembord synchroniseren", sync_clipboard_sub: "Tekst en links die je kopieert staan op beide apparaten",
  show_its_notifications: "Meldingen tonen", show_its_notifications_sub: "Meldingen van de telefoon verschijnen hier en als Windows-melding",
  accept_automatically: "Bestanden automatisch accepteren", accept_automatically_sub: "Zet uit om eerst gevraagd te worden",
  remove_device: "Dit apparaat verwijderen", remove_device_sub: "Het verlaat de kring op alle apparaten", remove: "Verwijderen",
  remove_confirm: "{0} verwijderen? Het verlaat de kring op elk apparaat en komt niet terug zonder nieuwe koppeling.", cancel: "Annuleren",
  now_playing: "Speelt nu", previous: "Vorige", next: "Volgende", play: "Afspelen", pause: "Pauzeren",
  wants_to_send: "{0} wil {1} bestand(en) sturen", accept: "Accepteren", decline: "Weigeren",
  local_network: "Lokaal netwerk", tailscale: "Tailscale", internet: "Internet",
  on_wifi: "Op wifi", on_wifi_ssid: "Op {0}", on_cellular: "Op mobiele data", on_cellular_roaming: "Op mobiele data (roaming)", on_ethernet: "Op Ethernet", no_network: "Geen netwerk",
  hotspot_on: "Hotspot aan", hotspot_cellular: "Hotspot aan, deelt mobiele data", dnd: "Niet storen", charging: "Aan het laden", battery: "Batterij",
  add_title: "Apparaat toevoegen", add_lead: "Open Tandem op je telefoon, kies Apparaat toevoegen en scan deze code. Hij werkt vijf minuten.",
  show_code: "Toon een code", enter_code: "Voer een code in", copy_link: "Kopieer link", copied: "Gekopieerd", paste_label: "Plak de link van het andere apparaat",
  pair: "Koppelen", pairing: "Koppelen…", paired_with: "Gekoppeld met {0}", pair_failed: "Koppelen is niet gelukt", new_code: "Nieuwe code", close: "Sluiten",
  welcome_title: "Koppel je telefoon", welcome_lead: "Tandem verbindt deze pc met je telefoon en Mac via je eigen netwerk, zonder iets ertussen.",
  step1: "Installeer Tandem op je telefoon", step2: "Open het en kies Apparaat toevoegen", step3: "Scan de code op dit scherm",
  firewall: "Als Windows naar de firewall vraagt, kies dan Toestaan op privénetwerken. Zonder dat kan je telefoon deze pc niet vinden.",
  title_shared: "Gedeeld", lead_shared: "Bestanden die naar je apparaten gingen en ervan kwamen", incoming: "Ontvangen", outgoing: "Verstuurd", open_folder: "Map openen", show_in_folder: "Toon in map",
  failed: "Mislukt", active: "Bezig", done: "Klaar",
  title_notifications: "Meldingen", lead_notifications: "Wat je telefoons lieten zien. Nieuwe verschijnen ook als Windows-melding.",
  no_notifications: "Nog geen meldingen", no_notifications_sub: "Als een telefoon een melding toont, staat die hier.", clear_all: "Alles wissen",
  this_pc: "Deze pc", pc_name: "Naam", save: "Bewaren", download_folder: "Ontvangen bestanden komen in", change: "Wijzigen",
  start_with_windows: "Starten met Windows", start_with_windows_sub: "Tandem wacht in het systeemvak zodat je telefoon deze pc altijd kan bereiken",
  close_to_tray: "Blijven draaien als het venster dicht is", close_to_tray_sub: "De sluitknop verbergt Tandem in het systeemvak",
  copy_codes: "Codes uit sms'jes kopiëren", copy_codes_sub: "Een verificatiecode van je telefoon gaat meteen naar het klembord",
  phone_notifications: "Telefoonmeldingen tonen als Windows-melding", phone_notifications_sub: "Antwoorden vanaf hier kan nog niet",
  language: "Taal", language_auto: "Zoals Windows", about: "Over", version: "Versie", engine_starting: "Starten…", engine_failed: "Tandem kon niet starten",
  panel_open: "Tandem openen", panel_none: "Nog geen apparaten", panel_connected: "{0} van {1} verbonden", pc_connected_summary: "{0} verbonden",
  mac: "Mac", phone: "Telefoon", pc: "Pc", unknown: "Apparaat", quit: "Afsluiten",
};

const tables = { en, nl };
let current = "en";

/** Picks the language from the choice of the person, or else from Windows. */
export function setLanguage(choice, system) {
  const tag = (choice && choice !== "auto" ? choice : system || navigator.language || "en").toLowerCase();
  current = tag.startsWith("nl") ? "nl" : "en";
  document.documentElement.lang = current;
}

export function language() {
  return current;
}

/** A word or sentence by key, with {0}, {1} filled in. */
export function t(key, ...args) {
  let text = tables[current][key] ?? tables.en[key] ?? key;
  args.forEach((value, i) => { text = text.replaceAll("{" + i + "}", value); });
  return text;
}
