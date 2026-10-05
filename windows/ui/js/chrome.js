// The frame of a window on Linux. The system draws its own bar there, with buttons in whatever style the desktop has, and it
// does not match the rest of the app, so the main window draws its own: a strip along the top to drag the window by, three
// round buttons, and thin edges to resize it with. Windows keeps its own frame.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { t } from "./i18n.js";
import { native } from "./backend.js";
import { state } from "./store.js";

const tauri = window.__TAURI__;
const current = () => (native ? tauri.window.getCurrentWindow() : null);

/** True when this window has to draw its own frame. */
export const framed = () => state.platform === "linux";

const glyph = {
  minimize: html`<svg viewBox="0 0 16 16" width="14" height="14"><path d="M3.5 8h9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" fill="none"/></svg>`,
  maximize: html`<svg viewBox="0 0 16 16" width="14" height="14"><rect x="3.5" y="3.5" width="9" height="9" rx="2" stroke="currentColor" stroke-width="1.5" fill="none"/></svg>`,
  restore: html`<svg viewBox="0 0 16 16" width="14" height="14"><rect x="3" y="5.5" width="7.5" height="7.5" rx="2" stroke="currentColor" stroke-width="1.5" fill="none"/><path d="M6 3.2h5a2 2 0 0 1 2 2V10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" fill="none"/></svg>`,
  close: html`<svg viewBox="0 0 16 16" width="14" height="14"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" fill="none"/></svg>`,
};

/** The three buttons of a window, as round buttons that grow a little when the pointer is on them. */
export function WindowButtons() {
  const [maximized, setMaximized] = useState(false);
  useEffect(() => {
    const win = current();
    if (!win) return;
    const look = () => win.isMaximized().then(setMaximized).catch(() => {});
    look();
    let off;
    win.onResized(look).then((f) => { off = f; });
    return () => off && off();
  }, []);
  const act = (what) => () => { const win = current(); if (win) win[what]().catch(() => {}); };
  return html`<div class="wbtns">
    <button class="wbtn" title=${t("win_minimize")} aria-label=${t("win_minimize")} onClick=${act("minimize")}>${glyph.minimize}</button>
    <button class="wbtn" title=${t(maximized ? "win_restore" : "win_maximize")} aria-label=${t(maximized ? "win_restore" : "win_maximize")} onClick=${act("toggleMaximize")}>${maximized ? glyph.restore : glyph.maximize}</button>
    <button class="wbtn close" title=${t("win_close")} aria-label=${t("win_close")} onClick=${act("close")}>${glyph.close}</button>
  </div>`;
}

/** The strip along the top of the page: it moves the window, and a double click makes it big or small again. */
export function Titlebar() {
  return html`<div class="titlebar" data-tauri-drag-region><${WindowButtons} /></div>`;
}

const edges = [
  ["n", "North"], ["s", "South"], ["e", "East"], ["w", "West"],
  ["nw", "NorthWest"], ["ne", "NorthEast"], ["sw", "SouthWest"], ["se", "SouthEast"],
];

/** Thin strips along the edges and corners that start a resize when they are pressed on, since there is no frame to grab. */
export function ResizeEdges() {
  return html`${edges.map(([name, direction]) => html`<div key=${name} class=${"edge edge-" + name}
    onMouseDown=${(e) => { if (e.button === 0) { const win = current(); if (win) win.startResizeDragging(direction).catch(() => {}); } }}></div>`)}`;
}
