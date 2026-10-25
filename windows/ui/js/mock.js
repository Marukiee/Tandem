// A pretend app for looking at the interface in a browser: a phone, a Mac, files on their way, music and
// notifications. It answers the same commands the Rust side does. Add ?mock=empty for a PC with nothing paired.
const listeners = {};
const empty = new URLSearchParams(location.search).get("mock") === "empty";

const cover = "data:image/svg+xml;utf8," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="#b5372b"/><circle cx="100" cy="100" r="58" fill="#1b1b1b"/><circle cx="100" cy="100" r="18" fill="#b5372b"/></svg>',
);

const now = Date.now();
const devices = empty ? [] : [
  { id: "phone", name: "Pixel 9", platform: "android", online: true, route: "lan", rttMs: 16, appVersion: "0.1.25", caps: ["files", "screen.host", "camera.host", "screen.extend.view", "wifi.join", "sms"],
    // ?calls=off: the phone may not place calls yet, which the call button in Messages says when it is pressed.
    features: [{ id: "calls", ready: new URLSearchParams(location.search).get("calls") !== "off", name: "Calls", why: "Tandem may not make calls on Pixel 9 yet. Allow it on the phone.", page: "access" }],
    clipboard: true, autoAccept: true, notifications: true, ble: false, vouchedByRemoved: false,
    status: { battery: { level: 80, charging: true, powerSave: false }, network: { kind: "cellular", ssid: null, metered: true, roaming: false, signal: 3 }, hotspot: true, dnd: false } },
  { id: "mac", name: "MacBook Pro", platform: "macos", online: true, route: "lan", rttMs: 4, appVersion: "0.1.25", caps: ["screen.host", "screen.view", "screen.extend.view", "audio.play"],
    features: [
      { id: "control", ready: false, name: "Control this Mac", why: "Accessibility is off for Tandem on this Mac", page: "accessibility" },
      { id: "screen", ready: true, name: "See this Mac's screen", why: "", page: null },
      { id: "media.control", ready: false, name: "Media buttons for Spotify and Music", why: "Tandem has not been allowed to control Spotify yet", page: "automation" },
    ],
    clipboard: true, autoAccept: false, notifications: true, ble: false, vouchedByRemoved: false,
    status: { battery: { level: 64, charging: false, powerSave: false }, network: { kind: "wifi", ssid: "Thuis", metered: false, roaming: false } } },
  { id: "linux", name: "ThinkPad", platform: "linux", online: false, route: null, rttMs: null, appVersion: "0.1.80", caps: ["ring"],
    clipboard: true, autoAccept: false, notifications: true, ble: false, vouchedByRemoved: false, status: {},
    lastSeen: { atMs: now - 2 * 3600000, ssid: "Thuis", route: "lan" } },
  { id: "old", name: "Xperia test", platform: "android", online: false, route: null, rttMs: null, appVersion: "0.1.20", caps: [],
    clipboard: true, autoAccept: true, notifications: true, ble: false, vouchedByRemoved: false, status: {} },
];

/** What the update shows when the page is asked for ?update=available, downloading, installing or failed. */
function updateFor(kind) {
  const base = { dismissed: "", version: "0.1.27", page: "https://github.com/Marukiee/Tandem/releases/latest" };
  switch (kind) {
    case "available": return { ...base, state: "available", notes: "" };
    case "downloading": return { ...base, state: "downloading", progress: 0.42 };
    case "installing": return { ...base, state: "installing" };
    case "failed": return { ...base, state: "failed", reason: "The download is damaged (the checksum does not match)" };
    default: return { state: "idle", dismissed: "" };
  }
}

const state = {
  ready: true, error: null, self: { id: "me", name: "My Laptop", port: 47820 }, version: "0.1.25", build: "preview", canShare: true, canHost: true, canBluetooth: true, canExtend: new URLSearchParams(location.search).get("platform") === "linux", platform: new URLSearchParams(location.search).get("platform") || "windows", input: { ok: new URLSearchParams(location.search).get("input") !== "no", why: "wayland" },
  devices,
  transfers: empty ? [] : [
    { id: "1-0-in", peer: "phone", name: "IMG_20261003_141201.jpg", incoming: true, state: "done", done: 4200000, total: 4200000, location: "C:\\Users\\Mark\\Downloads\\Tandem\\IMG_20261003_141201.jpg", updatedAt: now - 60000, startedAt: now - 62000 },
    { id: "2-0-out", peer: "phone", name: "Begroting 2027.xlsx", incoming: false, state: "done", done: 88000, total: 88000, updatedAt: now - 3600000, startedAt: now - 3601000 },
    { id: "3-0-in", peer: "mac", name: "Presentatie.pdf", incoming: true, state: "failed", error: "The connection dropped", done: 100, total: 900, updatedAt: now - 86400000, startedAt: now - 86400000 },
  ],
  offers: empty ? [] : [{ from: "mac", fromName: "MacBook Pro", offer: "7", origin: "files", items: [{ name: "Foto's vakantie.zip", size: 48000000, mime: "application/zip" }] }],
  notifications: empty ? [] : [
    { device: "phone", deviceName: "Pixel 9", key: "a", appId: "wa", appName: "WhatsApp", title: "Anna", text: "Zie je dat? Ik ben er over tien minuten.", ts: now - 120000, buttons: [] },
    { device: "phone", deviceName: "Pixel 9", key: "b", appId: "bank", appName: "Bank", title: "Code 482913", text: "Gebruik 482913 om in te loggen.", ts: now - 900000, otp: "482913", buttons: [] },
  ],
  settings: { closeToTray: true, copyCodes: true, phoneNotifications: true, remoteInput: false, systemMedia: true, autoUpdate: true, language: "auto", downloadDir: "", shareDevice: "", shareEdge: "", quickShare: true, keepWhenLidClosed: false, autoTailscale: true, sideCheck: true, sideCheckMinutes: 2, sendScreenshots: true },
  update: updateFor(new URLSearchParams(location.search).get("update")),
  autostart: true, downloadDir: "C:\\Users\\Mark\\Downloads\\Tandem", systemLanguage: navigator.language, build_: "",
};

const players = empty ? {} : {
  phone: [{ id: "pixel", app: "PixelPlayer", title: "I Only Watch It For The Weather", artist: "The Delegates", album: "", playing: true, positionMs: 78000, durationMs: 213000, canPrev: true, canNext: true, canSeek: true, art: "1" }],
};
const art = { 1: cover };

// Messages of the phone. ?sms=off, no_permission, offline or unavailable shows what the page says when it cannot.
const smsRefusal = new URLSearchParams(location.search).get("sms");
const hour = 3600000;
const smsConversations = [
  { thread: 1, addresses: ["+31612345678"], name: "Anna de Vries", snippet: "Zie je morgen bij het station!", date: now - hour / 6, unread: 2, fromMe: false },
  { thread: 2, addresses: ["+31687654321"], name: "", snippet: "Uw pakket wordt vandaag tussen 14:00 en 16:00 bezorgd.", date: now - 3 * hour, unread: 0, fromMe: false },
  { thread: 3, addresses: ["+31611111111", "+31622222222"], name: "Bob, Carla", snippet: "Wie neemt de taart mee?", date: now - 26 * hour, unread: 0, fromMe: false },
  { thread: 4, addresses: ["+31633333333"], name: "Mam", snippet: "Bel je me straks even?", date: now - 50 * hour, unread: 0, fromMe: true },
  { thread: 5, addresses: ["+31201234567"], name: "Tandarts", snippet: "Herinnering: uw afspraak is op dinsdag om 09:30.", date: now - 9 * 24 * hour, unread: 0, fromMe: false },
  { thread: 6, addresses: ["+31644444444"], name: "Lotte Bakker", snippet: "", date: now - 12 * 24 * hour, unread: 0, fromMe: false },
  { thread: 7, addresses: ["StripeLink"], name: "", snippet: "Uw code is 482913", date: now - 14 * 24 * hour, unread: 0, fromMe: false },
];
const smsMessage = (id, thread, body, ago, mine, extra = {}) => ({
  id, mms: false, thread, address: mine ? "" : (smsConversations.find((c) => c.thread === thread) || {}).addresses?.[0] || "", body, date: now - ago,
  status: mine ? "sent" : "received", read: true, pictures: [], ...extra,
});
const smsThreads = {
  1: [
    smsMessage(9, 1, "Zie je morgen bij het station!", hour / 6, false),
    smsMessage(8, 1, "Om half negen?", hour / 5, false),
    smsMessage(7, 1, "Top, ik neem de trein van 8:12. Dan ben ik er ruim op tijd en kunnen we nog even koffie halen voordat het begint.", hour / 3, true),
    smsMessage(6, 1, "", 2 * hour, false, { mms: true, pictures: [11] }),
    smsMessage(5, 1, "Kijk wat ik vond", 2 * hour + 60000, false),
    smsMessage(4, 1, "Lukt het morgen?", 25 * hour, true, { status: "failed" }),
    smsMessage(3, 1, "Ja hoor, prima", 26 * hour, false),
    smsMessage(2, 1, "Zullen we morgen samen gaan?", 26 * hour + 120000, true),
  ],
  2: [smsMessage(21, 2, "Uw pakket wordt vandaag tussen 14:00 en 16:00 bezorgd.", 3 * hour, false)],
  3: [
    smsMessage(33, 3, "Ik!", 26 * hour - 60000, true),
    smsMessage(32, 3, "En wie de kaarsjes?", 26 * hour + 30000, false, { address: "+31622222222" }),
    smsMessage(31, 3, "Wie neemt de taart mee?", 26 * hour, false, { address: "+31622222222" }),
    smsMessage(30, 3, "Ik neem de slingers mee", 27 * hour, false, { address: "+31611111111" }),
  ],
  4: [smsMessage(41, 4, "Bel je me straks even?", 50 * hour, true)],
  5: [smsMessage(51, 5, "Herinnering: uw afspraak is op dinsdag om 09:30.", 9 * 24 * hour, false)],
  6: [],
  7: [smsMessage(71, 7, "Uw code is 482913", 14 * 24 * hour, false)],
};
const smsPicture = "data:image/svg+xml;utf8," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="180"><rect width="240" height="180" fill="#7fb3d5"/><circle cx="180" cy="50" r="24" fill="#f7dc6f"/><path d="M0 180 L80 90 L140 150 L190 110 L240 160 L240 180 Z" fill="#1e8449"/></svg>',
);
let smsNext = 100;

function smsAnswer(command, args) {
  if (command === "sms_ask_access") return null;
  if (smsRefusal) throw smsRefusal;
  switch (command) {
    case "sms_conversations": return { items: smsConversations.filter((c) => !args.before || c.date <= args.before).slice(0, args.limit), more: false };
    case "sms_messages": return { thread: args.thread, items: smsThreads[args.thread] || [], more: false };
    case "sms_contacts": return [
      { name: "Anna de Vries", number: "+31612345678", label: "Mobiel" },
      { name: "Anne Jansen", number: "+31698765432", label: "Werk" },
    ].filter((c) => c.name.toLowerCase().includes(args.query.toLowerCase()) || c.number.includes(args.query));
    case "sms_picture": return smsPicture;
    case "sms_send": {
      let conversation = smsConversations.find((c) => c.addresses.join() === args.to.join());
      if (!conversation) {
        conversation = { thread: smsNext++, addresses: args.to, name: "", snippet: args.text, date: Date.now(), unread: 0, fromMe: true };
        smsConversations.unshift(conversation);
        smsThreads[conversation.thread] = [];
      }
      // The phone shows what it sent a moment later, and an answer comes in after that.
      setTimeout(() => {
        const sent = smsMessage(smsNext++, conversation.thread, args.text, 0, true, { date: Date.now() });
        emit("sms", { device: "phone", conversations: [{ ...conversation, snippet: args.text, date: sent.date, fromMe: true }], messages: [sent] });
      }, 700);
      setTimeout(() => {
        const reply = smsMessage(smsNext++, conversation.thread, "Helemaal goed!", 0, false, { date: Date.now(), read: false });
        emit("sms", { device: "phone", conversations: [{ ...conversation, snippet: reply.body, date: reply.date, fromMe: false, unread: 1 }], messages: [reply] });
      }, 3000);
      return conversation.thread;
    }
    default: return null;
  }
}

export function listen(event, handler) {
  (listeners[event] ||= []).push(handler);
}

function emit(event, payload) {
  (listeners[event] || []).forEach((h) => h(payload));
}

const qr = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29"><rect width="29" height="29" fill="#fff"/>' +
  Array.from({ length: 29 * 29 }, (_, i) => {
    const x = i % 29, y = Math.floor(i / 29);
    const finder = (x < 8 && y < 8) || (x > 20 && y < 8) || (x < 8 && y > 20);
    const on = finder ? (x % 7 === 0 || y % 7 === 0 || x % 7 === 6 || y % 7 === 6 || (x % 7 > 1 && x % 7 < 5 && y % 7 > 1 && y % 7 < 5)) : ((x * 7 + y * 13 + x * y) % 5 < 2);
    return on ? `<rect x="${x}" y="${y}" width="1" height="1" fill="#1d1b3a"/>` : "";
  }).join("") + "</svg>";

// The arrangement of the screens, with a simplified version of the snapping the app does, for looking at the interface in a browser.
const MAIN = { width: 1920, height: 1080 };
let layout = [{ id: "mac", edge: "left", offset: 100 }];
const SIZES = { mac: [1512, 982], phone: [412, 915] };
let guestsMock = false;
function arrangement() {
  return {
    main: MAIN,
    capture: { state: new URLSearchParams(location.search).get("capture") || "ready", reason: "no permission" },
    devices: devices.filter((d) => d.platform !== "android" || true).map((d) => {
      const spot = layout.find((s) => s.id === d.id);
      const size = SIZES[d.id];
      return { id: d.id, name: d.name, platform: d.platform, online: d.online, width: size && size[0], height: size && size[1], placed: spot ? { edge: spot.edge, offset: spot.offset } : null };
    }),
  };
}
function rectMock(p, a) {
  switch (p.edge) {
    case "left": return { x: -a.width, y: p.offset, width: a.width, height: a.height };
    case "right": return { x: MAIN.width, y: p.offset, width: a.width, height: a.height };
    case "top": return { x: p.offset, y: -a.height, width: a.width, height: a.height };
    default: return { x: p.offset, y: MAIN.height, width: a.width, height: a.height };
  }
}
function snapMock(a) {
  const gaps = [
    ["left", Math.abs(a.x + a.width), a.y, a.height, MAIN.height],
    ["right", Math.abs(a.x - MAIN.width), a.y, a.height, MAIN.height],
    ["top", Math.abs(a.y + a.height), a.x, a.width, MAIN.width],
    ["bottom", Math.abs(a.y - MAIN.height), a.x, a.width, MAIN.width],
  ].filter(([, gap, start, len, main]) => gap <= a.snap && Math.min(start + len, main) - Math.max(start, 0) >= Math.min(len, main) / 4);
  gaps.sort((x, y) => x[1] - y[1]);
  return gaps.length ? { edge: gaps[0][0], offset: Math.round(gaps[0][2]) } : null;
}

export async function call(command, args) {
  await new Promise((r) => setTimeout(r, 40));
  if (command.startsWith("sms_")) return smsAnswer(command, args);
  switch (command) {
    case "get_state": return state;
    case "get_players": return { players, art };
    case "features_local": return [
      { id: "control", ready: true, name: "Control this computer", why: "", page: null },
      { id: "microphone", ready: false, name: "Find the side by sound", why: "This computer has no microphone that Tandem can use", page: "sound" },
    ];
    case "features_open_here": case "features_open_on": return null;
    case "live_start": return 1;
    case "display_start": case "display_stop": return null;
    case "display_state": return [];
    case "fs_roots": return [{ name: "Phone", write: false }];
    case "fs_list": return args.path === "/Phone"
      ? [{ name: "DCIM", dir: true, size: 0, modifiedMs: 1730000000000, readonly: false }, { name: "Download", dir: true, size: 0, modifiedMs: 1730000000000, readonly: false },
         { name: "notes.txt", dir: false, size: 2048, modifiedMs: 1730000000000, readonly: false }]
      : [{ name: "IMG_0001.jpg", dir: false, size: 3400000, modifiedMs: 1730000000000, readonly: false }, { name: "clip.mp4", dir: false, size: 48200000, modifiedMs: 1730000000000, readonly: false }];
    case "fs_get": return { saved: args.paths, skippedFolders: 0 };
    case "clip_history_search": return [
      { id: 3, text: "https://example.com/some/long/link", from: "Pixel 9", at_ms: Date.now() - 120000, pinned: true },
      { id: 2, text: "Meeting at ten, room 4", from: "", at_ms: Date.now() - 3600000, pinned: false },
      { id: 4, text: "Dear all,\nHere are the notes from yesterday.\n1. The new pairing screen is live.\n2. The clipboard page scrolls now.\n3. Long texts can be pulled taller with the corner.\n4. Anything else can wait until Monday.\nThanks, Mark", from: "MacBook Pro", at_ms: Date.now() - 7200000, pinned: false },
    ].filter((i) => !args.query || i.text.toLowerCase().includes(args.query.toLowerCase()));
    case "clip_history_pin": case "clip_history_remove": case "clip_history_clear": case "clip_history_copy": return null;
    case "files_policy": case "files_update": return { enabled: true, write: false, delete: false, hidden: false, shares: [{ name: "Documents", path: "C:\\Users\\Mark\\Documents", write: false }, { name: "Pictures", path: "C:\\Users\\Mark\\Pictures", write: true }] };
    case "files_add_folder": return { enabled: true, write: false, delete: false, hidden: false, shares: [] };
    case "clip_history_info": return { count: 2, pinned: 1, bytes: 62 };
    // `?focus=none` is a desktop without a Do Not Disturb; Windows only reads it.
    case "focus_info": return { read: new URLSearchParams(location.search).get("focus") !== "none", set: new URLSearchParams(location.search).get("platform") === "linux" };
    case "access_status": return { wayland: new URLSearchParams(location.search).get("platform") === "linux", inputAllowed: true, screenAllowed: false, input: { ok: true, why: "" }, canHost: true };
    case "portal_forget": return null;
    case "settings_export": return "C:\\Users\\Mark\\tandem-settings.json";
    case "settings_import": return state.settings;
    case "whats_new": return [
      { version: "0.1.74", date: "2026-10-09", en: { title: "A steadier shared mouse", new: ["Drag screens next to each other", "Linux updates itself"] }, nl: { title: "Een stabielere gedeelde muis", new: ["Sleep schermen naast elkaar", "Linux werkt zichzelf bij"] } },
      { version: "0.1.73", date: "2026-10-09", en: { title: "Fixes", new: ["The pointer comes back at once"] }, nl: { title: "Fixes", new: ["De muis komt direct terug"] } },
    ];
    case "arrange_state": return arrangement();
    case "arrange_snap": { const p = snapMock(args); return p ? { ...p, ...rectMock(p, args) } : null; }
    case "arrange_place": {
      const p = snapMock(args);
      layout = layout.filter((s) => s.id !== args.id);
      if (p) layout.push({ id: args.id, ...p });
      return arrangement();
    }
    case "arrange_remove": layout = layout.filter((s) => s.id !== args.id); return arrangement();
    case "media_policy": case "media_policy_set": return { screen: "ask", control: "ask" };
    case "ssh_probe": return args.id === "mac" ? "192.168.1.20" : null;
    case "ssh_open": return null;
    case "find_sounds": return { chosen: "C:\\Windows\\Media\\Alarm01.wav", ready: true, sounds: [
      { path: "C:\\Windows\\Media\\Alarm01.wav", name: "Alarm01", group: "Windows" }, { path: "C:\\Windows\\Media\\Ring01.wav", name: "Ring01", group: "Windows" },
      { path: "C:\\Windows\\Media\\Afternoon\\Windows Ding.wav", name: "Windows Ding", group: "Afternoon" }] };
    case "find_choose": case "find_preview": case "find_stop": case "ring": return null;
    case "output_state": return null;
    case "output_start": emit("output", args.id); return null;
    case "output_stop": emit("output", null); return null;
    case "ssh_info": return { label: "ssh-preview", platform: new URLSearchParams(location.search).get("platform") || "windows" };
    case "create_pairing": return { uri: "tandem://pair?c=PREVIEW", code: "4821 7093", expiresAtMs: Date.now() + 300000, qr };
    case "pair": return "phone";
    case "send_clipboard": return args.ids.length;
    case "wifi_here": return "Thuis";
    case "wifi_share": return "Wi-Fi sent to " + (devices.find((d) => d.id === args.id)?.name ?? "?");
    case "pick_and_send":
    case "send_paths": {
      const id = "9-0-out";
      let done = 0;
      const total = 12000000;
      const timer = setInterval(() => {
        done = Math.min(total, done + 1500000);
        emit("transfer", { id, peer: args.ids[0], name: "Voorbeeld.mov", incoming: false, state: done >= total ? "done" : "active", done, total, speed: 6e6, updatedAt: Date.now(), startedAt: Date.now() - 2000 });
        if (done >= total) clearInterval(timer);
      }, 400);
      return { sent: 1, offline: 0, folders: 0 };
    }
    case "qs_state": return {
      enabled: true,
      peers: [{ id: "abcd", name: "Pixel 9", kind: "phone" }, { id: "efgh", name: "Galaxy S26", kind: "phone" }],
      incoming: [
        { id: 1, sender: "Pixel 9", pin: "4821", files: [{ name: "IMG_20261005_141201.jpg", size: 4200000 }, { name: "Menu.pdf", size: 880000 }], texts: [], accepted: false, done: 0, total: 5080000, saved: null, link: null, failure: null },
        { id: 2, sender: "Galaxy S26", pin: "1093", files: [], texts: [{ kind: "url", title: "https://tandem.markmaaktmedia.nl" }], accepted: true, done: 0, total: 0, saved: [], link: "https://tandem.markmaaktmedia.nl", failure: null },
      ],
      outgoing: [], problem: null,
    };
    case "ask_state": return [
      { id: 1, title: "Alex's MacBook Pro wants to see your screen", body: "It sees everything on your screen and can use your mouse and keyboard until you stop it. You can stop it in the window of Tandem.", allow: "Allow", always: "Always allow", deny: "Deny", control: true },
    ];
    case "ask_answer": return null;
    case "qs_respond": case "qs_dismiss": case "qs_pick_and_send": case "qs_send_clipboard": case "qs_open_link": case "qs_send_paths": return null;
    case "guests_by_default": return guestsMock;
    case "set_guests_by_default": guestsMock = args.on; return null;
    case "set_settings": Object.assign(state.settings, args.patch); return state.settings;
    case "set_autostart": state.autostart = args.enabled; return args.enabled;
    case "check_update": state.update = { state: "up-to-date", dismissed: state.update.dismissed }; return state.update;
    case "dismiss_update": state.update = { ...state.update, dismissed: args.version }; return null;
    case "set_device_settings": {
      const d = devices.find((x) => x.id === args.id);
      if (d) Object.assign(d, { clipboard: args.clipboard, autoAccept: args.autoAccept, notifications: args.notifications });
      emit("devices", devices);
      return null;
    }
    default: return null;
  }
}
