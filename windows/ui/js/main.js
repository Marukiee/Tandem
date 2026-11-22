// The main window: the devices on the left, a page on the right.
import { html, render, useEffect, useState } from "../vendor/preact-htm.js";
import { call, connect, listenDrops, native } from "./backend.js";
import { DeviceGlyph, platformIcon, watchPhoneId } from "./components.js";
import { Icon } from "./icons.js";
import { ResizeEdges, Titlebar, framed } from "./chrome.js";
import { setLanguage, t } from "./i18n.js";
import { ClipboardPage, DevicePage, HealthPage, StepGoalDialog, WatchPage, WatchSettingsDialog, watchName, FilesPage, NotificationsPage, PairPanel, SettingsPage, SharedPage, Welcome, sendPaths } from "./pages.js";
import { say, selectedDevice, set, state, useStore } from "./store.js";
import { MessagesPage, offersMessages } from "./messages.js";
import { CallDialog, useCallAsk } from "./calls.js";
import { TourPage, maybeShowTour } from "./tour.js";
import { dropPaths, markDropTarget } from "./drops.js";
import { watchTips } from "./tips.js";

// The watch only talks to its phone, but it is a device of its own: its page goes by way of the phone.
function watchRow(d, folded) {
  const watch = d.status && d.status.watch;
  if (!watch || !watch.paired || d.id !== watchPhoneId(state.devices)) return null;
  const reachable = d.online && watch.online;
  const on = (state.page === "watch" || state.page === "health") && state.selected === d.id;
  const status = reachable ? t("connected") : t("watch_away");
  return html`<div class=${"row devrow" + (on ? " selected" : "") + (reachable ? "" : " dim")} key=${d.id + "-watch"} title=${folded ? watchName(d) + " · " + status : null}
    onClick=${() => set({ selected: d.id, page: "watch" })}>
    <span class="navicon"><${Icon} name="device-watch" size=${20} /></span>
    <div class="words grow">
      <div class="name ellipsis">${watchName(d)}</div>
      <div class="line2"><span class=${"sub ellipsis" + (reachable ? " ok" : "")}>${status}</span>${watch.level != null && html`<span class="small muted bat">${watch.level}%</span>`}</div>
    </div>
    <span class="mini">${t("watch")}</span>
  </div>`;
}

/** What is new in a release, as at most two short lines: the bullets of its summary, or else its first lines. */
export function updateNotes(summary, max = 2) {
  const lines = String(summary || "").split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const bullets = lines.filter((l) => l.startsWith("\u2022 ")).map((l) => l.slice(2).trim());
  return (bullets.length ? bullets : lines.filter((l) => !l.startsWith("#"))).slice(0, max);
}

// The download and install states carry no notes or size, so what the card showed when it was first offered is kept.
let offered = { version: "", notes: [], size: 0, progress: 0 };

/**
 * The update card. One card for the whole update: Later and Update share its width, Update becomes a bar while the file comes
 * in, and when it is in the update installs by itself and Tandem opens again. The three lines of text and of the button are all
 * in the page, and the phase only says which of them is in view, so a change is a slide and not a swap.
 */
function UpdateBanner() {
  const u = state.update;
  const failed = (e) => say(String(e && e.message ? e.message : e));
  if (u.state === "available" && u.version !== u.dismissed) offered = { version: u.version, notes: updateNotes(u.notes), size: u.size || 0, progress: 0 };
  const phase = u.state === "available" && u.version !== u.dismissed ? 0 : u.state === "downloading" ? 1 : u.state === "installing" ? 2 : -1;
  if (phase >= 0) {
    if (phase === 1) offered.progress = u.progress || 0;
    const pct = phase === 2 ? 100 : Math.round(offered.progress * 100);
    const at = (i) => (i === phase ? "" : i < phase ? " up" : " down");
    const mb = (n) => (n / 1e6).toFixed(1);
    const version = u.version || offered.version;
    return html`<div class=${"update card-new" + (phase === 2 ? " installing" : "")}>
      <div class="update-head"><span class=${"update-tile" + (phase === 2 ? " ok" : "")}>
          <span class=${"ic" + (phase === 2 ? " out" : "")}><${Icon} name="sparkles" size=${22} /></span>
          <span class=${"ic" + (phase === 2 ? "" : " out")}><${Icon} name="check" size=${24} stroke=${2.4} /></span></span>
        <div class="grow update-lines">
          <div class=${"ln" + at(0)}><div class="t">${t("update_new_version", version)}</div><div class="s">${t("update_you_have_now", state.version)}</div></div>
          <div class=${"ln" + at(1)}><div class="t">${t("update_new_version", version)}</div><div class="s">${offered.size ? t("update_size_of", mb(offered.size * offered.progress), mb(offered.size)) : pct + "%"}</div></div>
          <div class=${"ln" + at(2)}><div class="t">${t("update_installing_version", version)}</div><div class="s">${t("update_restarts")}</div></div>
        </div></div>
      <ul class=${"update-notes" + (phase === 2 ? " shut" : "")}>${offered.notes.map((n) => html`<li key=${n}>${n}</li>`)}</ul>
      <div class="update-actions">
        <button class=${"btn small later" + (phase === 0 ? "" : " shut")} disabled=${phase !== 0} onClick=${() => call("dismiss_update", { version: u.version }).catch(failed)}>${t("later")}</button>
        <button class=${"btn small accent go" + (phase === 1 ? " bar" : phase === 2 ? " installing" : "")} disabled=${phase !== 0} onClick=${() => call("install_update").catch(failed)}>
          <i class="fill" style=${`width:${phase === 1 ? pct : 0}%`}></i>
          <span class=${"lab" + at(0)}>${t("update_button")}</span>
          <span class=${"lab" + at(1)}>${pct}%</span>
          <span class=${"lab" + at(2)}>${t("update_installing_dots")}</span>
        </button>
      </div>
    </div>`;
  }
  if (u.state === "failed") {
    return html`<div class="update bad">
      <div class="update-head"><span class="update-icon bad"><${Icon} name="alert-triangle" size=${15} /></span>
        <div class="grow"><div class="t">${t("update_failed")}</div><div class="s">${u.reason}</div></div></div>
      <div class="update-actions">
        <button class="btn accent wide" onClick=${() => call(u.version ? "install_update" : "check_update").catch(failed)}>${t("update_again")}</button>
      </div>
    </div>`;
  }
  return null;
}

/** Who is looking at the screen of this computer now, with a way to stop it. */
function HostingBanner() {
  if (!state.hosting.length) return null;
  const names = state.hosting.map((h) => h.name).join(", ");
  const control = state.hosting.some((h) => h.control);
  return html`<div class="update bad">
    <div class="update-head"><span class="update-icon bad"><${Icon} name="device-desktop" size=${15} /></span>
      <div class="grow"><div class="t">${t("hosting_title", names)}</div><div class="s">${control ? t("hosting_control") : t("hosting_view")}</div></div></div>
    <div class="update-actions"><button class="btn small danger" onClick=${() => call("host_stop").catch(() => {})}>${t("hosting_stop")}</button></div>
  </div>`;
}

// A screen of another computer is shown over this one: the way back is Alt+Tab, and here is the button that ends it.
function DisplayBanner() {
  if (!state.displays.length) return null;
  const names = state.displays.map((d) => d.name).join(", ");
  return html`<div class="update">
    <div class="update-head"><span class="update-icon"><${Icon} name="device-laptop" size=${15} /></span>
      <div class="grow"><div class="t">${t("display_shown", names)}</div></div></div>
    <div class="update-actions"><button class="btn small danger" onClick=${() => call("display_stop").catch(() => {})}>${t("display_stop")}</button></div>
  </div>`;
}

/** A word for each device in the narrow sidebar: the one that tells it from the others. */
function shortNames(devices) {
  const words = devices.map((d) => d.name.trim().split(/\s+/));
  // A first word they all share (the name of their owner) says nothing in a column this narrow.
  const shared = words.length > 1 && words.every((w) => w.length > 1 && w[0] === words[0][0]);
  return Object.fromEntries(devices.map((d, i) => {
    const rest = words[i].slice(shared ? 1 : 0);
    const whole = rest.join(" ");
    const word = whole.length <= 10 ? whole : rest[0];
    return [d.id, word.length > 10 ? word.slice(0, 9) + "…" : word];
  }));
}

const narrowWindow = window.matchMedia("(max-width: 899px)");
/** True while the window is too narrow for the wide sidebar. */
function useNarrow() {
  const [narrow, setNarrow] = useState(narrowWindow.matches);
  useEffect(() => {
    const look = () => setNarrow(narrowWindow.matches);
    narrowWindow.addEventListener("change", look);
    return () => narrowWindow.removeEventListener("change", look);
  }, []);
  return narrow;
}

// The phone whose messages the one row opens: the one that was open last, else the first that is connected.
let lastPhone = null;

function Nav() {
  const narrow = useNarrow();
  // A banner in the narrow sidebar is only its icon. A press on it opens the sidebar over the page until the pointer leaves.
  const [peek, setPeek] = useState(false);
  const unread = state.notifications.length;
  const phones = state.devices.filter(offersMessages);
  if (state.page === "messages" && phones.some((d) => d.id === state.selected)) lastPhone = state.selected;
  const phone = phones.find((d) => d.id === lastPhone) || phones.find((d) => d.online) || phones[0];
  // One sidebar with two widths. It folds by itself when the window is narrow, and on Settings, which brings a list of its own:
  // two wide columns side by side leave no room for the page.
  const folded = (narrow || state.page === "settings") && !peek;
  const short = shortNames(state.devices);
  const online = state.devices.filter((d) => d.online).length;
  const pageRow = (id, icon, label, tail, more = {}) => html`<div class=${"row pagerow" + ((more.on ?? state.page === id) ? " selected" : "") + (more.dim ? " dim" : "")}
    title=${folded ? label : null} onClick=${more.go || (() => set({ page: id }))}>
    <span class="navicon"><${Icon} name=${icon} size=${20} /></span><span class="words grow ellipsis">${label}</span>${tail}<span class="mini">${label}</span></div>`;
  return html`<nav class=${"nav" + (folded ? " fold" : "") + (peek ? " peek" : "")} onMouseLeave=${() => peek && setPeek(false)}>
    <div class="navhead" data-tauri-drag-region><span class="t">${t("devices")}</span>
      ${state.devices.length > 0 && html`<span class="count"><i class=${"dot" + (online ? " on" : "")}></i>${t("connected_of", online, state.devices.length)}</span>`}</div>
    ${state.devices.map((d) => {
      const battery = d.status && d.status.battery;
      const on = (state.page === "device" || state.page === "files") && state.selected === d.id;
      const status = d.online ? t("connected") + (d.rttMs != null ? " · " + d.rttMs + " ms" : "") : d.ble ? t("over_bluetooth") : t("not_connected");
      return [html`<div class=${"row devrow" + (on ? " selected" : "") + (d.online ? "" : " dim")} key=${d.id} data-device-id=${d.id} title=${folded ? d.name + " · " + status : null}
        onClick=${() => set({ selected: d.id, page: "device" })}>
        <${DeviceGlyph} device=${d} size=${40} ring=${true} />
        <div class="words grow">
          <div class="name ellipsis">${d.name}</div>
          <div class="line2"><span class=${"sub ellipsis" + (d.online ? " ok" : d.ble ? " ble" : "")}>${status}</span>
            ${battery && html`<span class="small muted bat">${battery.charging && d.online && html`<${Icon} name="bolt" size=${13} filled=${true} class="ok-text" />`}${battery.level}%</span>`}</div>
        </div>
        <span class="mini">${short[d.id]}</span>
      </div>`, watchRow(d, folded)];
    })}
    <div class="navline"></div>
    ${pageRow("shared", "files", t("shared"), state.transfers.length > 0 && html`<span class="tail small muted">${state.transfers.length}</span>`)}
    ${/* One row for the messages, of whichever phone was open last: with two phones the page itself has the choice. */ null}
    ${phone && pageRow("messages", "message", t("sms_messages"), phones.length > 1 && html`<span class="tail small muted ellipsis">${phone.name}</span>`,
      { on: state.page === "messages", dim: !phone.online, go: () => set({ selected: phone.id, page: "messages" }) })}
    ${pageRow("clipboard", "clipboard", t("clipboard"))}
    ${pageRow("notifications", "bell", t("notifications"), unread > 0 && html`<span class="badge">${unread > 99 ? "99+" : unread}</span>`)}
    <div class="spacer"></div>
    ${/* Above the foot and not under it: here they take their room from the empty middle, and no row moves when one comes or goes. */ null}
    <div class="banners" onClick=${() => folded && setPeek(true)}>
      <${HostingBanner} />
      <${DisplayBanner} />
      <${UpdateBanner} />
    </div>
    ${/* Settings is not content: it sits at the foot with Add a device, apart from the pages above. */ null}
    ${pageRow("settings", "settings", t("settings"))}
    <div class="row pagerow addrow" title=${folded ? t("add_device") : null} onClick=${() => set({ dialog: { kind: "pair" } })}>
      <span class="navicon"><span class="plusdisc"><${Icon} name="plus" size=${14} /></span></span><span class="words grow ellipsis">${t("add_device")}</span></div>
  </nav>`;
}

/** The phone keeps the name and tells everyone. The plain name is no name of its own: it then follows the language of each device. */
function RenameWatch({ dialog, close }) {
  const d = state.devices.find((x) => x.id === dialog.id);
  const [name, setName] = useState(d ? watchName(d) : "");
  const save = async () => {
    close();
    try { await call("rename_watch", { id: dialog.id, name: name.trim() === t("watch") ? "" : name.trim() }); } catch (e) { say(String(e)); }
  };
  return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
    <div class="dialog"><h2>${t("watch_rename_title")}</h2><p class="muted" style="margin-top:0">${t("watch_name_hint")}</p>
      <input type="text" style="width:100%" maxlength="40" value=${name} onInput=${(e) => setName(e.target.value)} onKeyDown=${(e) => e.key === "Enter" && save()} />
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:14px">
        <button class="btn" onClick=${close}>${t("cancel")}</button>
        <button class="btn accent" onClick=${save}>${t("save")}</button>
      </div></div></div>`;
}

// Which device of a backup comes back on this computer. The one it recognised is chosen already; one of another kind stays in the
// list, grey, and says why when clicked.
function RestoreDialog({ dialog, close }) {
  const [picked, setPicked] = useState(dialog.recognised);
  const [why, setWhy] = useState("");
  const kindName = (d) => ({ android: t("restore_phone"), macos: "Mac", mac: "Mac", windows: "Windows", linux: "Linux" })[d.platform] || "";
  const restore = async () => {
    const device = dialog.devices[picked];
    close();
    try {
      const now = await call("settings_restore", { index: picked });
      set({ settings: now });
      setLanguage(now.language, state.systemLanguage);
      say(t("backup_restored", device.name || t("restore_computer")));
    } catch (e) { say(String(e)); }
  };
  return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
    <div class="dialog restore"><h2>${t("restore_title")}</h2>
      <p class="muted" style="margin-top:0">${dialog.recognised == null ? t("restore_pick") : t("restore_recognised")}</p>
      <div class="restore-list">${dialog.devices.map((d, i) => html`
        <button class=${"restore-row" + (d.fits ? "" : " off") + (picked === i ? " picked" : "")}
          onClick=${() => { if (d.fits) { setPicked(i); setWhy(""); } else setWhy(d.kind === "mac" ? t("restore_not_mac") : t("restore_not_phone")); }}>
          <span class="restore-dot"><${Icon} name=${platformIcon(d.platform === "mac" ? "macos" : d.platform)} size=${18} /></span>
          <span class="restore-text"><span class="restore-name">${d.name || t("restore_computer")}</span>
            <span class="restore-detail">${i === dialog.recognised ? t("restore_this") : [kindName(d), d.model].filter(Boolean).join(" · ")}</span></span>
          ${d.fits && html`<span class=${"restore-mark" + (picked === i ? " on" : "")}>${picked === i && html`<${Icon} name="check" size=${14} />`}</span>`}
        </button>`)}</div>
      <div class=${"restore-why" + (why ? " shown" : "")}><${Icon} name="info-circle" size=${16} /><span>${why}</span></div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="btn" onClick=${close}>${t("cancel")}</button>
        <button class="btn accent" disabled=${picked == null} onClick=${restore}>${t("restore_button")}</button>
      </div></div></div>`;
}

function Dialogs() {
  const dialog = state.dialog;
  // Escape closes the dialog, as a click beside it does.
  useEffect(() => {
    if (!dialog) return undefined;
    const onKey = (e) => { if (e.key === "Escape") set({ dialog: null }); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [dialog]);
  if (!dialog) return null;
  const close = () => set({ dialog: null });
  if (dialog.kind === "pair") {
    return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
      <div class="dialog"><h2>${t("add_title")}</h2><p class="muted" style="margin-top:0">${t("add_lead")}</p>
        <${PairPanel} onDone=${close} />
        <div style="text-align:right;margin-top:14px"><button class="btn" onClick=${close}>${t("close")}</button></div>
      </div></div>`;
  }
  if (dialog.kind === "call") return html`<${CallDialog} dialog=${dialog} />`;
  if (dialog.kind === "restore") return html`<${RestoreDialog} dialog=${dialog} close=${close} />`;
  if (dialog.kind === "rename-watch") return html`<${RenameWatch} dialog=${dialog} close=${close} />`;
  if (dialog.kind === "watch-settings") return html`<${WatchSettingsDialog} dialog=${dialog} close=${close} />`;
  if (dialog.kind === "step-goal") return html`<${StepGoalDialog} dialog=${dialog} close=${close} />`;
  if (dialog.kind === "unpair-watch") {
    const d = state.devices.find((x) => x.id === dialog.id);
    return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
      <div class="dialog"><h2>${t("watch_unpair_title", d ? watchName(d) : t("watch"))}</h2><p>${t("watch_unpair_sub")}</p>
        <div style="display:flex;gap:8px;justify-content:flex-end">
          <button class="btn" onClick=${close}>${t("cancel")}</button>
          <button class="btn accent" onClick=${async () => { close(); try { await call("unpair_watch", { id: dialog.id }); } catch (e) { say(String(e)); } }}>${t("watch_unpair_button")}</button>
        </div></div></div>`;
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
  else if (state.page === "clipboard") body = html`<${ClipboardPage} />`;
  else if (state.page === "files") body = html`<${FilesPage} />`;
  else if (state.page === "notifications") body = html`<${NotificationsPage} />`;
  else if (state.page === "settings") body = html`<${SettingsPage} />`;
  else if (state.page === "tour") body = html`<${TourPage} />`;
  else if (state.page === "messages" && selectedDevice()) body = html`<${MessagesPage} device=${selectedDevice()} key=${state.selected} />`;
  else if (state.page === "watch" && selectedDevice()?.status?.watch?.paired) body = html`<${WatchPage} device=${selectedDevice()} key=${state.selected + "-watch"} />`;
  else if (state.page === "health" && selectedDevice()?.status?.watch?.paired) body = html`<${HealthPage} key=${state.selected + "-health"} />`;
  else if (state.devices.length === 0) body = html`<${Welcome} />`;
  else {
    const device = selectedDevice();
    body = device ? html`<${DevicePage} device=${device} key=${device.id} />` : null;
  }
  return html`<main class="page">${body}</main>`;
}

function App() {
  useStore();
  useCallAsk();
  useEffect(() => { setLanguage(state.settings.language, state.systemLanguage); }, [state.settings.language, state.systemLanguage]);
  return html`<div id="app" class=${framed() ? "framed" : ""}>
    ${framed() && html`<${Titlebar} />`}
    <${Nav} /><${Page} /><${Dialogs} />
    ${framed() && html`<${ResizeEdges} />`}
    ${state.say && html`<div class="toast" role="status">${state.say}</div>`}
  </div>`;
}

await connect();
// In a plain browser a page can be asked for by name, which is how each one is looked at while it is being made.
if (!native) {
  const wanted = new URLSearchParams(location.search).get("page");
  if (wanted) set({ page: wanted });
  const section = new URLSearchParams(location.search).get("section");
  if (section) set({ settingsWanted: { section, at: Date.now() } });
}
// The tour once after an update that brings a new one, or because its reminder was pressed while there was no window yet.
if (await call("tour_pending").catch(() => false)) set({ page: "tour" });
else if (native) maybeShowTour();
setLanguage(state.settings.language, state.systemLanguage);
// A drop goes where it was let go (drops.js). The drop zone on the page of a device lights up only when that is where it would go.
await listenDrops(
  (over, at) => { const target = markDropTarget(over ? at : null); set({ dropping: !!(target && target.page) }); },
  (paths, at) => { set({ dropping: false }); dropPaths(paths, at); },
);
render(html`<${App} />`, document.getElementById("root"));
watchTips();
