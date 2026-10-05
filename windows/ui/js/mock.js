// A pretend app for looking at the interface in a browser: a phone, a Mac, files on their way, music and
// notifications. It answers the same commands the Rust side does. Add ?mock=empty for a PC with nothing paired.
const listeners = {};
const empty = new URLSearchParams(location.search).get("mock") === "empty";

const cover = "data:image/svg+xml;utf8," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="#b5372b"/><circle cx="100" cy="100" r="58" fill="#1b1b1b"/><circle cx="100" cy="100" r="18" fill="#b5372b"/></svg>',
);

const now = Date.now();
const devices = empty ? [] : [
  { id: "phone", name: "Maruks Telefoon", platform: "android", online: true, route: "lan", rttMs: 16, appVersion: "0.1.25", caps: [],
    clipboard: true, autoAccept: true, notifications: true, ble: false, vouchedByRemoved: false,
    status: { battery: { level: 80, charging: true, powerSave: false }, network: { kind: "cellular", ssid: null, metered: true, roaming: false, signal: 3 }, hotspot: true, dnd: false } },
  { id: "mac", name: "MacBook Pro", platform: "macos", online: true, route: "lan", rttMs: 4, appVersion: "0.1.25", caps: [],
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
  ready: true, error: null, self: { id: "me", name: "Laptop van Mark", port: 47820 }, version: "0.1.25", build: "preview",
  devices,
  transfers: empty ? [] : [
    { id: "1-0-in", peer: "phone", name: "IMG_20261003_141201.jpg", incoming: true, state: "done", done: 4200000, total: 4200000, location: "C:\\Users\\Mark\\Downloads\\Tandem\\IMG_20261003_141201.jpg", updatedAt: now - 60000, startedAt: now - 62000 },
    { id: "2-0-out", peer: "phone", name: "Begroting 2027.xlsx", incoming: false, state: "done", done: 88000, total: 88000, updatedAt: now - 3600000, startedAt: now - 3601000 },
    { id: "3-0-in", peer: "mac", name: "Presentatie.pdf", incoming: true, state: "failed", error: "The connection dropped", done: 100, total: 900, updatedAt: now - 86400000, startedAt: now - 86400000 },
  ],
  offers: empty ? [] : [{ from: "mac", fromName: "MacBook Pro", offer: "7", origin: "files", items: [{ name: "Foto's vakantie.zip", size: 48000000, mime: "application/zip" }] }],
  notifications: empty ? [] : [
    { device: "phone", deviceName: "Maruks Telefoon", key: "a", appId: "wa", appName: "WhatsApp", title: "Anna", text: "Zie je dat? Ik ben er over tien minuten.", ts: now - 120000, buttons: [] },
    { device: "phone", deviceName: "Maruks Telefoon", key: "b", appId: "bank", appName: "Bank", title: "Code 482913", text: "Gebruik 482913 om in te loggen.", ts: now - 900000, otp: "482913", buttons: [] },
  ],
  settings: { closeToTray: true, copyCodes: true, phoneNotifications: true, remoteInput: false, systemMedia: true, autoUpdate: true, language: "auto", downloadDir: "" },
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

export async function call(command, args) {
  await new Promise((r) => setTimeout(r, 40));
  switch (command) {
    case "get_state": return state;
    case "get_players": return { players, art };
    case "live_start": return 1;
    case "create_pairing": return { uri: "tandem://pair?c=PREVIEW", expiresAtMs: Date.now() + 300000, qr };
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
