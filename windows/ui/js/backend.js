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

/** Files dropped on the window by the shell, as paths. */
export async function listenDrops(onHover, onDrop) {
  if (!native) return;
  await tauri.event.listen("tauri://drag-enter", () => onHover(true));
  await tauri.event.listen("tauri://drag-leave", () => onHover(false));
  await tauri.event.listen("tauri://drag-drop", (e) => {
    onHover(false);
    onDrop(e.payload.paths || []);
  });
}

function pick(devices) {
  if (devices.some((d) => d.id === state.selected)) return state.selected;
  return (devices.find((d) => d.online) || devices[0] || {}).id || null;
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
    hasLid: !!snapshot.hasLid,
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
  await listen("transfer", (item) => set({ transfers: upsert(state.transfers, item) }));
  await listen("offer", (offer) => set({ offers: [...state.offers, offer] }));
  await listen("offer-gone", ({ from, offer }) => set({ offers: state.offers.filter((o) => !(o.from === from && o.offer === offer)) }));
  await listen("notification", (n) => set({ notifications: [n, ...state.notifications.filter((x) => !(x.device === n.device && x.key === n.key))].slice(0, 200) }));
  await listen("notification-removed", ({ device, key }) => set({ notifications: state.notifications.filter((n) => !(n.device === device && n.key === key)) }));
  await listen("players", (players) => set({ players }));
  await listen("art", ({ key, uri }) => set({ art: { ...state.art, [key]: uri } }));
  await listen("say", (text) => say(text));
  await listen("hosting", (hosting) => set({ hosting }));
  // The event leaves out what the person decided about it, so that stays.
  await listen("update", (update) => set({ update: { dismissed: state.update.dismissed, ...update } }));
  await listen("engine-ready", async () => apply(await call("get_state")));
  await listen("engine-error", (error) => set({ error }));
  await listen("paired", ({ id, name }) => {
    set({ dialog: null, selected: id, page: "device" });
    return name;
  });
}
