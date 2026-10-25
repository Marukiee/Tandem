// The tour of what Tandem can do on this computer: the list of What works (features.rs), each thing that is not ready with set up, later and
// not for me. What waits for later comes back once, as one notice a few days on (features.rs). Shown once after an update that brings a new
// tour, and from What works in the settings.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { set, state } from "./store.js";

/** A new version shows the tour once more after an update, also to whoever has used Tandem for long. */
export const TOUR_VERSION = 1;

const REMIND_AFTER_MS = 3 * 24 * 60 * 60 * 1000;

/** Saves a change of the tour in the settings, and keeps the settings of the window up to date with it. */
async function save(change) {
  set({ settings: await call("set_settings", { patch: change }) });
}

/** Opens the tour when this version has not been shown yet. */
export function maybeShowTour() {
  if ((state.settings.tourSeen || 0) >= TOUR_VERSION) return;
  save({ tourSeen: TOUR_VERSION }).catch(() => {});
  set({ page: "tour" });
}

export function TourPage() {
  // Nothing until the list is in, or the page says for a moment that all is done.
  const [features, setFeatures] = useState(null);
  useEffect(() => {
    save({ tourSeen: TOUR_VERSION }).catch(() => {});
    // Permissions change outside the app, so the list is looked at again while the page is open.
    const load = () => call("features_local").then((list) => setFeatures(list.filter((f) => f.id !== "wifi.share"))).catch(() => {});
    load();
    const timer = setInterval(load, 2000);
    return () => clearInterval(timer);
  }, []);
  if (!features) return html`<div class="wrap"><h1 style="margin:4px 0 0">${t("tour_title")}</h1></div>`;
  const s = state.settings;
  const later = s.tourLater || [];
  const skipped = s.tourSkip || [];
  const todo = features.filter((f) => !f.ready && !later.includes(f.id) && !skipped.includes(f.id));
  const waiting = features.filter((f) => !f.ready && later.includes(f.id));
  const notForMe = features.filter((f) => !f.ready && skipped.includes(f.id));
  const works = features.filter((f) => f.ready);

  const setUp = (f) => f.page && call("features_open_here", { page: f.page }).catch(() => {});
  const putOff = (f) => save({
    tourLater: [...later.filter((id) => id !== f.id), f.id],
    tourSkip: skipped.filter((id) => id !== f.id),
    ...(s.tourRemindAt ? {} : { tourRemindAt: Date.now() + REMIND_AFTER_MS }),
  });
  const skip = (f) => save({ tourSkip: [...skipped.filter((id) => id !== f.id), f.id], tourLater: later.filter((id) => id !== f.id) });
  const undo = (f) => save({ tourSkip: skipped.filter((id) => id !== f.id), tourLater: later.filter((id) => id !== f.id) });

  const row = (f, buttons, note) => html`<div class="setting feature tour-row" key=${f.id}>
    <span class=${"feature-mark " + (f.ready ? "ok" : "todo")}><${Icon} name=${f.ready ? "circle-check" : iconOf(f.id)} size=${18} /></span>
    <div class="grow">
      <div class="t">${f.name}</div>
      ${(note || (!f.ready && f.why)) && html`<div class="s">${note || f.why}</div>`}
      ${buttons && html`<div class="tour-actions">${buttons}</div>`}
    </div>
  </div>`;
  const setUpButton = (f) => html`<button class="btn accent small" disabled=${!f.page} onClick=${() => setUp(f)}>${t("tour_set_up")}</button>`;

  return html`<div class="wrap" style="display:flex;flex-direction:column;gap:12px">
    <h1 style="margin:4px 0 0">${t("tour_title")}</h1>
    <div class="muted">${todo.length === 0 ? t("tour_all_done") : t("tour_intro", todo.length)}</div>
    ${todo.length > 0 && html`<h2>${t("tour_todo")}</h2><div class="card flush">${todo.map((f) => row(f, html`${setUpButton(f)}
      <button class="btn small" onClick=${() => putOff(f)}>${t("tour_later")}</button>
      <button class="btn small" onClick=${() => skip(f)}>${t("tour_not_for_me")}</button>`))}</div>`}
    ${waiting.length > 0 && html`<h2>${t("tour_waiting")}</h2><div class="card flush">${waiting.map((f) => row(f, html`${setUpButton(f)}
      <button class="btn small" onClick=${() => skip(f)}>${t("tour_not_for_me")}</button>`, t("tour_waiting_note")))}</div>`}
    ${works.length > 0 && html`<h2>${t("tour_works")}</h2><div class="card flush">${works.map((f) => row(f, null))}</div>`}
    ${notForMe.length > 0 && html`<h2>${t("tour_not_for_you")}</h2><div class="card flush" style="opacity:.7">${notForMe.map((f) => row(f,
      html`<button class="btn small" onClick=${() => undo(f)}>${t("tour_undo")}</button>`, " "))}</div>`}
    <div style="display:flex;justify-content:center;margin-top:8px">
      <button class="btn accent" onClick=${() => set({ page: state.devices.length ? "device" : "settings" })}>${t("tour_done")}</button>
    </div>
  </div>`;
}

function iconOf(id) {
  switch (id) {
    case "control": return "pointer";
    case "screen": return "device-desktop";
    case "microphone": return "microphone";
    case "ring": return "bell-ringing";
    case "focus": return "moon";
    case "bluetooth": return "bluetooth";
    case "codes": return "key";
    default: return "alert-triangle";
  }
}
