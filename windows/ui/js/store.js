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
  // Incoming transfers that stopped halfway and wait for Go on or Throw away.
  halfway: [],
  // What is underway and incoming (P3b), one row per transfer and device: running, waiting for a yes, queued for a device that is away, failed.
  underway: [],
  notifications: [],
  players: {},
  art: {},
  settings: { closeToTray: true, copyCodes: true, phoneNotifications: true, remoteInput: false, systemMedia: true, phoneSound: true, autoUpdate: true, language: "auto", downloadDir: "", shareDevice: "", shareEdge: "", quickShare: false, keepWhenLidClosed: false, autoTailscale: true },
  // This PC can be the main computer of a shared mouse and keyboard (Windows only).
  canShare: false,
  // "windows" or "linux": a few words and what is possible differ.
  platform: "windows",
  // This computer can show its screen to other devices (see host.rs), and who is looking at it now.
  canHost: false,
  // This computer is a laptop whose lid can be asked about (Linux).
  hasLid: false,
  hosting: [],
  displays: [],
  webcams: [],
  // Whether this system lets Tandem move the pointer and press keys, and if not why (wayland, no-display).
  input: { ok: true, why: "" },
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

/**
 * The devices in the order they had before, from one start to the next. The core puts the connected ones first, and at a start they
 * connect one by one, so every row jumped about (2026-11-25). A new device goes at the end, a removed one leaves the order.
 */
function inKeptOrder(devices) {
  let kept = [];
  try { kept = JSON.parse(localStorage.getItem("deviceOrder") || "[]"); } catch { kept = []; }
  const present = new Set(devices.map((d) => d.id));
  const order = (Array.isArray(kept) ? kept : []).filter((id) => present.has(id));
  for (const d of devices) if (!order.includes(d.id)) order.push(d.id);
  try { localStorage.setItem("deviceOrder", JSON.stringify(order)); } catch { /* kept for this run only */ }
  return [...devices].sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
}

export function set(patch) {
  if (Array.isArray(patch.devices)) patch = { ...patch, devices: inKeptOrder(patch.devices) };
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
