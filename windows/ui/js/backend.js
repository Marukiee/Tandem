// The way to the app behind the window. Inside Tandem it is the Rust side; in a plain browser, which is how the
// interface is looked at and tried while it is being made, a pretend one (mock.js) answers instead.
import { setPlatform } from "./i18n.js";
import { say, set, state } from "./store.js";

const tauri = window.__TAURI__;
export const native = Boolean(tauri);
let mock = null;

async function pretend() {
  mock ??= await import("./mock.js");
  return mock;
}

export async function call(command, args = {}) {
  if (native) return tauri.core.invoke(command, args);
  return (await pretend()).call(command, args);
}

export async function listen(event, handler) {
  if (native) return tauri.event.listen(event, (e) => handler(e.payload));
  return (await pretend()).listen(event, handler);
}

/**
 * Files dragged over and dropped on this window by the shell: `onHover(over, at)` while a drag hangs over it, `onDrop(paths, at)` when it
 * is let go. `at` is where, in CSS pixels of the window. Only the drags of this window: a plain listener hears those of every window, so a
 * drop on the tray panel would also reach the main window. In a plain browser `mockDrag(x, y)` and `mockDrop(paths, x, y)` stand in.
 */
export async function listenDrops(onHover, onDrop) {
  if (!native) {
    window.mockDrag = (x, y) => onHover(x != null, x != null ? { x, y } : null);
    window.mockDrop = (paths, x, y) => { onHover(false, null); onDrop(paths, { x, y }); };
    return;
  }
  // The position comes in physical pixels.
  const at = (p) => (p && p.position ? { x: p.position.x / devicePixelRatio, y: p.position.y / devicePixelRatio } : null);
  const webview = tauri.webview && tauri.webview.getCurrentWebview && tauri.webview.getCurrentWebview();
  if (webview && webview.onDragDropEvent) {
    await webview.onDragDropEvent((e) => {
      const p = e.payload;
      if (p.type === "drop") { onHover(false, null); onDrop(p.paths || [], at(p)); }
      else if (p.type === "leave") onHover(false, null);
      else onHover(true, at(p));
    });
    return;
  }
  await tauri.event.listen("tauri://drag-enter", (e) => onHover(true, at(e.payload)));
  await tauri.event.listen("tauri://drag-over", (e) => onHover(true, at(e.payload)));
  await tauri.event.listen("tauri://drag-leave", () => onHover(false, null));
  await tauri.event.listen("tauri://drag-drop", (e) => {
    onHover(false, null);
    onDrop(e.payload.paths || [], at(e.payload));
  });
}

/** The device the app opened on while nothing was connected yet: the first one to connect takes over, unless the person went elsewhere. */
let waitingOn = null;

function pick(devices) {
  const connected = devices.find((d) => d.online);
  const current = devices.find((d) => d.id === state.selected);
  if (current && (current.id !== waitingOn || current.online || !connected || state.page !== "device")) return current.id;
  const chosen = connected || devices[0];
  waitingOn = chosen && !chosen.online ? chosen.id : null;
  return chosen ? chosen.id : null;
}

function upsert(list, item, limit = 60) {
  return [item, ...list.filter((x) => x.id !== item.id)].slice(0, limit);
}

function apply(snapshot) {
  setPlatform(snapshot.platform);
  set({
    ready: snapshot.ready,
    error: snapshot.error,
    self: snapshot.self,
    version: snapshot.version,
    build: snapshot.build,
    canShare: !!snapshot.canShare,
    platform: snapshot.platform || "windows",
    canHost: !!snapshot.canHost,
    canExtend: !!snapshot.canExtend,
    canBluetooth: !!snapshot.canBluetooth,
    hasLid: !!snapshot.hasLid,
    canKeepAwake: !!snapshot.canKeepAwake,
    input: snapshot.input || { ok: true, why: "" },
    devices: snapshot.devices,
    transfers: snapshot.transfers,
    offers: snapshot.offers,
    notifications: snapshot.notifications,
    settings: snapshot.settings,
    autostart: snapshot.autostart,
    update: snapshot.update || state.update,
    downloadDir: snapshot.downloadDir,
    systemLanguage: snapshot.systemLanguage,
    selected: pick(snapshot.devices),
  });
  // A ring of the battery card in the panel asked for this device's page while this window did not exist yet.
  if (snapshot.openDevice) set({ selected: snapshot.openDevice, page: "device" });
}

/** Loads everything once and then follows what the app reports. */
export async function connect() {
  // Listen before looking: the engine starts on its own and can be ready (or have failed) at any moment, and an event
  // that comes while the first look is taken must not be lost. The look itself then overwrites whatever came early.
  await follow();
  apply(await call("get_state"));
  const media = await call("get_players");
  set({ players: media.players, art: media.art });

  // Belt and braces: should the news of the engine ever not arrive, ask again until it is there.
  if (!state.ready && !state.error) {
    const timer = setInterval(async () => {
      if (state.ready || state.error) return clearInterval(timer);
      apply(await call("get_state"));
    }, 500);
  }
}

async function follow() {
  await listen("devices", (devices) => set({ devices, selected: pick(devices) }));
  await listen("health", (health) => set({ health }));
  call("health_records").then((health) => set({ health })).catch(() => {});
  await listen("open-device", (id) => set({ selected: id, page: "device" }));
  await listen("open-page", (page) => set({ page }));
  await listen("transfer", (item) => set({ transfers: upsert(state.transfers, item) }));
  await listen("transfer_gone", (id) => set({ transfers: state.transfers.filter((t) => t.id !== id) }));
  await listen("offer", (offer) => set({ offers: [...state.offers, offer] }));
  await listen("offer-gone", ({ from, offer }) => set({ offers: state.offers.filter((o) => !(o.from === from && o.offer === offer)) }));
  await listen("notification", (n) => set({ notifications: [n, ...state.notifications.filter((x) => !(x.device === n.device && x.key === n.key))].slice(0, 200) }));
  await listen("notification-removed", ({ device, key }) => set({ notifications: state.notifications.filter((n) => !(n.device === device && n.key === key)) }));
  await listen("players", (players) => set({ players }));
  await listen("art", ({ key, uri }) => set({ art: { ...state.art, [key]: uri } }));
  await listen("say", (text) => say(text));
  await listen("hosting", (hosting) => set({ hosting }));
  // Keep awake can turn itself off (a low battery) or be switched from the menu of the tray icon.
  await listen("settings", (settings) => set({ settings }));
  await listen("display", (displays) => set({ displays }));
  await listen("webcams", (webcams) => set({ webcams }));
  // The event leaves out what the person decided about it, so that stays.
  await listen("update", (update) => set({ update: { dismissed: state.update.dismissed, ...update } }));
  // A person picked in the search of the desktop: the Messages of that phone, with the question before the call over it.
  const ask = (callAsk) => callAsk && set({ selected: callAsk.id, page: "messages", callAsk: { ...callAsk, at: Date.now() } });
  await listen("call-ask", ask);
  call("call_ask_pending").then(ask).catch(() => {});
  // Something picked in the search of the desktop: a device page, a conversation, the files of a device or a section of the settings.
  const go = (place) => {
    if (!place) return;
    const at = Date.now();
    const patch = { page: place.page };
    if (place.selected) patch.selected = place.selected;
    if (place.page === "settings") patch.settingsWanted = { section: place.section, at };
    if (place.page === "messages" && place.thread != null) patch.smsWanted = { device: place.selected, thread: place.thread, at };
    set({ dialog: null, ...patch });
  };
  await listen("search-open", go);
  call("search_open_pending").then(go).catch(() => {});
  await listen("engine-ready", async () => apply(await call("get_state")));
  await listen("engine-error", (error) => set({ error }));
  await listen("paired", ({ id, name }) => {
    set({ dialog: null, selected: id, page: "device" });
    return name;
  });
}
