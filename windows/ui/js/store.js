// One place for what the windows show. A change tells every part of the interface that listens.
import { useEffect, useState } from "../vendor/preact-htm.js";

export const state = {
  ready: false,
  error: null,
  self: null,
  version: "",
  build: "",
  devices: [],
  transfers: [],
  offers: [],
  notifications: [],
  players: {},
  art: {},
  settings: { closeToTray: true, copyCodes: true, phoneNotifications: true, remoteInput: false, systemMedia: true, autoUpdate: true, language: "auto", downloadDir: "", shareDevice: "", shareEdge: "", quickShare: false },
  // This PC can be the main computer of a shared mouse and keyboard (Windows only).
  canShare: false,
  // How the update stands: idle, checking, up-to-date, available, downloading, installing or failed.
  update: { state: "idle", dismissed: "" },
  autostart: false,
  downloadDir: "",
  systemLanguage: "",
  page: "device", // device, shared, notifications, settings
  selected: null,
  dialog: null, // { kind: "pair" } or { kind: "remove", id }
  say: null,
};

const listeners = new Set();

export function set(patch) {
  Object.assign(state, patch);
  listeners.forEach((listener) => listener());
}

/** Re-draws the component whenever the state changes. */
export function useStore() {
  const [, bump] = useState(0);
  useEffect(() => {
    const listener = () => bump((n) => n + 1);
    listeners.add(listener);
    return () => listeners.delete(listener);
  }, []);
  return state;
}

let sayTimer = null;

/** A short message at the bottom of the window. */
export function say(text) {
  set({ say: text });
  clearTimeout(sayTimer);
  sayTimer = setTimeout(() => set({ say: null }), 3200);
}

export const connected = (d) => d.online;

export function selectedDevice() {
  return state.devices.find((d) => d.id === state.selected) || null;
}
