// The main window: the devices on the left, a page on the right.
import { html, render, useEffect } from "../vendor/preact-htm.js";
import { call, connect, listenDrops, native } from "./backend.js";
import { DeviceGlyph } from "./components.js";
import { Icon } from "./icons.js";
import { setLanguage, t } from "./i18n.js";
import { DevicePage, NotificationsPage, PairPanel, SettingsPage, SharedPage, Welcome, sendPaths } from "./pages.js";
import { say, selectedDevice, set, state, useStore } from "./store.js";

/** The update, at the foot of the sidebar: what is out, how far the download is, and what went wrong. */
function UpdateBanner() {
  const u = state.update;
  const failed = (e) => say(String(e && e.message ? e.message : e));
  if (u.state === "available" && u.version !== u.dismissed) {
    return html`<div class="update">
      <div class="update-head"><span class="update-icon"><${Icon} name="arrow-down" size=${15} /></span>
        <div class="grow"><div class="t">${t("update_available", u.version)}</div><div class="s">${t("update_you_have", state.version)}</div></div></div>
      <div class="update-actions">
        <button class="btn small ghost" onClick=${() => call("dismiss_update", { version: u.version }).catch(failed)}>${t("later")}</button>
        <button class="btn small accent" onClick=${() => call("install_update").catch(failed)}>${t("update_and_restart")}</button>
      </div>
    </div>`;
  }
  if (u.state === "downloading") {
    const pct = Math.round((u.progress || 0) * 100);
    return html`<div class="update">
      <div class="update-head"><span class="update-icon"><${Icon} name="arrow-down" size=${15} /></span>
        <div class="grow"><div class="t">${t("update_downloading", u.version)}</div></div><span class="small muted">${pct}%</span></div>
      <div class="prog"><i style=${`width:${pct}%`}></i></div>
    </div>`;
  }
  if (u.state === "installing") {
    return html`<div class="update"><div class="update-head"><span class="update-icon"><${Icon} name="refresh" size=${15} /></span>
      <div class="grow"><div class="t">${t("update_installing")}</div><div class="s">${t("update_reopens")}</div></div></div></div>`;
  }
  if (u.state === "failed") {
    return html`<div class="update bad">
      <div class="update-head"><span class="update-icon bad"><${Icon} name="alert-triangle" size=${15} /></span>
        <div class="grow"><div class="t">${t("update_failed")}</div><div class="s">${u.reason}</div></div></div>
      <div class="update-actions">
        ${u.page && html`<button class="btn small ghost" onClick=${() => call("open_url", { url: u.page }).catch(failed)}>${t("update_open_page")}</button>`}
        ${u.version && html`<button class="btn small" onClick=${() => call("install_update").catch(failed)}>${t("try_again")}</button>`}
      </div>
    </div>`;
  }
  return null;
}

function Nav() {
  const unread = state.notifications.length;
  return html`<nav class="nav">
    <div class="brand"><img src="icons/brand.png" alt="" />Tandem<span class="tag">${t("experimental")}</span></div>
    <div class="label">${t("devices")}</div>
    ${state.devices.map((d) => {
      const battery = d.status && d.status.battery;
      const on = state.page === "device" && state.selected === d.id;
      return html`<div class=${"row" + (on ? " selected" : "") + (d.online ? "" : " dim")} key=${d.id} onClick=${() => set({ selected: d.id, page: "device" })}>
        <${DeviceGlyph} device=${d} size=${36} />
        <div class="grow">
          <div class="name ellipsis">${d.name}</div>
          <div class=${"sub" + (d.online ? " ok" : "")}>${d.online ? t("connected") + (d.rttMs != null ? " · " + d.rttMs + " ms" : "") : t("not_connected")}</div>
        </div>
        ${battery && html`<span class="small muted" style="display:flex;align-items:center;gap:2px">
          ${battery.charging && html`<${Icon} name="bolt" size=${13} filled=${true} class="ok-text" />`}${battery.level}%</span>`}
      </div>`;
    })}
    <div class="row addrow" onClick=${() => set({ dialog: { kind: "pair" } })}><${Icon} name="plus" size=${18} />${t("add_device")}</div>
    <div class="spacer"></div>
    <div class=${"row" + (state.page === "shared" ? " selected" : "")} onClick=${() => set({ page: "shared" })}><${Icon} name="files" size=${19} /><span class="grow">${t("shared")}</span></div>
    <div class=${"row" + (state.page === "notifications" ? " selected" : "")} onClick=${() => set({ page: "notifications" })}>
      <${Icon} name="bell" size=${19} /><span class="grow">${t("notifications")}</span>${unread > 0 && html`<span class="badge">${unread > 99 ? "99+" : unread}</span>`}</div>
    <div class=${"row" + (state.page === "settings" ? " selected" : "")} onClick=${() => set({ page: "settings" })}><${Icon} name="settings" size=${19} /><span class="grow">${t("settings")}</span></div>
    <${UpdateBanner} />
  </nav>`;
}

function Dialogs() {
  const dialog = state.dialog;
  if (!dialog) return null;
  const close = () => set({ dialog: null });
  if (dialog.kind === "pair") {
    return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
      <div class="dialog"><h2>${t("add_title")}</h2><p class="muted" style="margin-top:0">${t("add_lead")}</p>
        <${PairPanel} onDone=${close} />
        <div style="text-align:right;margin-top:14px"><button class="btn" onClick=${close}>${t("close")}</button></div>
      </div></div>`;
  }
  if (dialog.kind === "remove") {
    const d = state.devices.find((x) => x.id === dialog.id);
    return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
      <div class="dialog"><h2>${t("remove_device")}</h2><p>${t("remove_confirm", d ? d.name : "")}</p>
        <div style="display:flex;gap:8px;justify-content:flex-end">
          <button class="btn" onClick=${close}>${t("cancel")}</button>
          <button class="btn accent" onClick=${async () => { close(); try { await call("remove_device", { id: dialog.id }); } catch (e) { say(String(e)); } }}>${t("remove")}</button>
        </div></div></div>`;
  }
  return null;
}

function Page() {
  if (state.error) return html`<main class="page"><div class="wrap"><div class="card empty"><${Icon} name="alert-triangle" size=${34} /><div style="font-weight:600">${t("engine_failed")}</div><div class="small">${state.error}</div><div style="margin-top:12px"><button class="btn small" onClick=${() => call("open_logs").catch(() => {})}>${t("open_logs")}</button></div></div></div></main>`;
  if (!state.ready) return html`<main class="page"><div class="wrap"><div class="empty muted">${t("engine_starting")}</div></div></main>`;
  let body;
  if (state.page === "shared") body = html`<${SharedPage} />`;
  else if (state.page === "notifications") body = html`<${NotificationsPage} />`;
  else if (state.page === "settings") body = html`<${SettingsPage} />`;
  else if (state.devices.length === 0) body = html`<${Welcome} />`;
  else {
    const device = selectedDevice();
    body = device ? html`<${DevicePage} device=${device} key=${device.id} />` : null;
  }
  return html`<main class="page">${body}</main>`;
}

function App() {
  useStore();
  useEffect(() => { setLanguage(state.settings.language, state.systemLanguage); }, [state.settings.language, state.systemLanguage]);
  return html`<div id="app">
    <${Nav} /><${Page} /><${Dialogs} />
    ${state.say && html`<div class="toast" role="status">${state.say}</div>`}
  </div>`;
}

await connect();
// In a plain browser a page can be asked for by name, which is how each one is looked at while it is being made.
if (!native) {
  const wanted = new URLSearchParams(location.search).get("page");
  if (wanted) set({ page: wanted });
}
setLanguage(state.settings.language, state.systemLanguage);
await listenDrops(
  (over) => set({ dropping: over }),
  (paths) => { const device = selectedDevice(); if (device && device.online) sendPaths([device.id], paths); },
);
render(html`<${App} />`, document.getElementById("root"));
