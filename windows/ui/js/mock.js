// A pretend app for looking at the interface in a browser: a phone, a Mac, files on their way, music and
// notifications. It answers the same commands the Rust side does. Add ?mock=empty for a PC with nothing paired.
const listeners = {};
const empty = new URLSearchParams(location.search).get("mock") === "empty";

const cover = "data:image/svg+xml;utf8," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="#b5372b"/><circle cx="100" cy="100" r="58" fill="#1b1b1b"/><circle cx="100" cy="100" r="18" fill="#b5372b"/></svg>',
);

const now = Date.now();
const devices = empty ? [] : [
  { id: "phone", name: "Pixel 9", platform: "android", online: true, route: "lan", rttMs: 16, appVersion: "0.1.25", caps: ["files", "screen.host", "camera.host"],
    clipboard: true, autoAccept: true, notifications: true, ble: false, vouchedByRemoved: false,
    status: { battery: { level: 80, charging: true, powerSave: false }, network: { kind: "cellular", ssid: null, metered: true, roaming: false, signal: 3 }, hotspot: true, dnd: false } },
  { id: "mac", name: "MacBook Pro", platform: "macos", online: true, route: "lan", rttMs: 4, appVersion: "0.1.25", caps: ["screen.host", "screen.view"],
    clipboard: true, autoAccept: false, notifications: true, ble: false, vouchedByRemoved: false,
    status: { battery: { level: 64, charging: false, powerSave: false }, network: { kind: "wifi", ssid: "Thuis", metered: false, roaming: false } } },
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
  ready: true, error: null, self: { id: "me", name: "My Laptop", port: 47820 }, version: "0.1.25", build: "preview", canShare: true, canHost: true, platform: new URLSearchParams(location.search).get("platform") || "windows", input: { ok: new URLSearchParams(location.search).get("input") !== "no", why: "wayland" },
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
  settings: { closeToTray: true, copyCodes: true, phoneNotifications: true, remoteInput: false, systemMedia: true, autoUpdate: true, language: "auto", downloadDir: "", shareDevice: "", shareEdge: "", quickShare: true, keepWhenLidClosed: false, autoTailscale: true },
  update: updateFor(new URLSearchParams(location.search).get("update")),
  autostart: true, downloadDir: "C:\\Users\\Mark\\Downloads\\Tandem", systemLanguage: navigator.language, build_: "",
};

const players = empty ? {} : {
  phone: [{ id: "pixel", app: "PixelPlayer", title: "I Only Watch It For The Weather", artist: "The Delegates", album: "", playing: true, positionMs: 78000, durationMs: 213000, canPrev: true, canNext: true, canSeek: true, art: "1" }],
};
const art = { 1: cover };

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
  switch (command) {
    case "get_state": return state;
    case "get_players": return { players, art };
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
    case "ssh_info": return { label: "ssh-preview", platform: new URLSearchParams(location.search).get("platform") || "windows" };
    case "create_pairing": return { uri: "tandem://pair?c=PREVIEW", code: "4821 7093", expiresAtMs: Date.now() + 300000, qr };
    case "pair": return "phone";
    case "send_clipboard": return args.ids.length;
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
      { id: 1, title: "Maruks MacBook Pro wants to see your screen", body: "It sees everything on your screen and can use your mouse and keyboard until you stop it. You can stop it in the window of Tandem.", allow: "Allow", always: "Always allow", deny: "Deny", control: true },
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
