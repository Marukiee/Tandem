// The pages of the main window.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call, native } from "./backend.js";
import { BatteryRing, Chip, DeviceGlyph, PlayerCard, Switch, ago, deviceChips, fmtSize } from "./components.js";
import { Icon } from "./icons.js";
import { language, setLanguage, t } from "./i18n.js";
import { say, set, state } from "./store.js";

const failed = (e) => say(String(e && e.message ? e.message : e));

/** What came of a send, as a short message. */
export function afterSend(result) {
  if (result.folders) say(t("folders_skipped"));
  else if (result.sent) say(t("files_offered"));
  if (result.offline) say(t("offline_n", result.offline));
}

export async function sendPaths(ids, paths) {
  if (!ids.length || !paths.length) return;
  try { afterSend(await call("send_paths", { ids, paths })); } catch (e) { failed(e); }
}

// ---- Pairing ------------------------------------------------------------------------

export function PairPanel({ onDone }) {
  const [tab, setTab] = useState("show");
  const [offer, setOffer] = useState(null);
  const [link, setLink] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const [copied, setCopied] = useState(false);

  const fresh = async () => {
    try { setOffer(await call("create_pairing")); } catch (e) { setProblem(String(e)); }
  };
  useEffect(() => { fresh(); return () => { call("cancel_pairing"); }; }, []);

  const pair = async () => {
    setBusy(true);
    setProblem("");
    try {
      const id = await call("pair", { uri: link });
      set({ selected: id, page: "device" });
      onDone && onDone();
    } catch (e) {
      setProblem(t("pair_failed") + ": " + String(e));
    }
    setBusy(false);
  };
  const copy = async () => {
    try { await navigator.clipboard.writeText(offer.uri); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch (e) { failed(e); }
  };

  return html`<div>
    <div class="seg">
      <button class=${tab === "show" ? "on" : ""} onClick=${() => setTab("show")}>${t("show_code")}</button>
      <button class=${tab === "enter" ? "on" : ""} onClick=${() => setTab("enter")}>${t("enter_code")}</button>
    </div>
    ${tab === "show" && html`
      <div class="qr" dangerouslySetInnerHTML=${{ __html: offer ? offer.qr : "" }}></div>
      <div style="display:flex;gap:8px">
        <input type="text" class="grow" readonly value=${offer ? offer.uri : ""} onFocus=${(e) => e.target.select()} />
        <button class="btn" onClick=${copy}><${Icon} name=${copied ? "check" : "copy"} size=${16} />${copied ? t("copied") : t("copy_link")}</button>
      </div>
      <div style="margin-top:10px"><button class="btn small" onClick=${fresh}><${Icon} name="refresh" size=${14} />${t("new_code")}</button></div>`}
    ${tab === "enter" && html`
      <label class="muted small" for="link">${t("paste_label")}</label>
      <div style="display:flex;gap:8px;margin-top:6px">
        <input id="link" type="text" class="grow" placeholder="tandem://pair?…" value=${link} onInput=${(e) => setLink(e.target.value)} />
        <button class="btn accent" disabled=${busy || !link.trim()} onClick=${pair}>${busy ? t("pairing") : t("pair")}</button>
      </div>`}
    ${problem && html`<p style="color:var(--bad)">${problem}</p>`}
    <p class="muted small" style="margin-bottom:0">${t("firewall")}</p>
  </div>`;
}

export function Welcome() {
  return html`<div class="wrap">
    <div>
      <h1>${t("welcome_title")}</h1>
      <p class="lead">${t("welcome_lead")}</p>
    </div>
    <div class="card" style="display:grid;grid-template-columns:1fr 1fr;gap:24px;align-items:start">
      <ol style="margin:0;padding-left:20px;display:flex;flex-direction:column;gap:10px">
        <li>${t("step1")}</li><li>${t("step2")}</li><li>${t("step3")}</li>
      </ol>
      <${PairPanel} />
    </div>
  </div>`;
}

// ---- A device -----------------------------------------------------------------------

function Offers({ device }) {
  return state.offers.filter((o) => o.from === device.id).map((o) => html`<div class="banner" key=${o.offer}>
    <${Icon} name="download" size=${20} />
    <div class="grow">
      <div style="font-weight:600">${t("wants_to_send", o.fromName, o.items.length)}</div>
      <div class="small muted ellipsis">${o.items.map((i) => i.name + " (" + fmtSize(i.size) + ")").join(", ")}</div>
    </div>
    <button class="btn accent small" onClick=${() => call("accept_offer", { from: o.from, offer: o.offer }).catch(failed)}>${t("accept")}</button>
    <button class="btn small" onClick=${() => call("decline_offer", { from: o.from, offer: o.offer }).catch(failed)}>${t("decline")}</button>
  </div>`);
}

export function TransferRow({ item }) {
  const peer = state.devices.find((d) => d.id === item.peer);
  const active = item.state === "active";
  const pct = item.total ? Math.min(100, Math.round((item.done / item.total) * 100)) : 0;
  const detail = active
    ? fmtSize(item.done) + " / " + fmtSize(item.total) + (item.speed ? " · " + fmtSize(item.speed) + "/s" : "")
    : item.state === "failed" ? (item.error || t("failed")) : fmtSize(item.total);
  return html`<div class="item">
    <${Icon} name=${item.incoming ? "arrow-down" : "arrow-up"} size=${18} class=${item.state === "failed" ? "bad" : ""} />
    <div class="grow">
      <div class="ellipsis" style="font-weight:500">${item.name}</div>
      <div class="small muted ellipsis" style=${item.state === "failed" ? "color:var(--bad)" : ""}>${peer ? peer.name + " · " : ""}${detail}${!active && item.updatedAt ? " · " + ago(item.updatedAt) : ""}</div>
      ${active && html`<div class="prog"><i style=${`width:${pct}%`}></i></div>`}
    </div>
    ${item.state === "done" && item.incoming && item.location && html`
      <button class="ib" title=${t("show_in_folder")} onClick=${() => call("reveal_path", { path: item.location }).catch(failed)}><${Icon} name="folder-open" size=${18} /></button>`}
  </div>`;
}

function SettingRow({ icon, title, sub, on, onChange, children }) {
  return html`<div class="setting">
    <${Icon} name=${icon} size=${20} />
    <div class="grow"><div class="t">${title}</div>${sub && html`<div class="s">${sub}</div>`}</div>
    ${children || html`<${Switch} on=${on} onChange=${onChange} label=${title} />`}
  </div>`;
}

export function DevicePage({ device }) {
  const [ringing, setRinging] = useState(false);
  const players = device.online ? state.players[device.id] || [] : [];
  const transfers = state.transfers.filter((x) => x.peer === device.id).slice(0, 5);
  const ids = [device.id];

  const send = async () => { try { afterSend(await call("pick_and_send", { ids })); } catch (e) { failed(e); } };
  const clipboard = async () => {
    try { await call("send_clipboard", { ids }); say(t("clipboard_sent")); }
    catch (e) { failed(e === "no-text" ? t("no_text") : e); }
  };
  const ring = async () => { const on = !ringing; setRinging(on); try { await call("ring", { id: device.id, on }); } catch (e) { failed(e); setRinging(false); } };
  const setting = (patch) => call("set_device_settings", {
    id: device.id, clipboard: device.clipboard, autoAccept: device.autoAccept, notifications: device.notifications, ...patch,
  }).catch(failed);
  const battery = device.status && device.status.battery;
  const hasCap = (cap) => Array.isArray(device.caps) && device.caps.includes(cap);
  const show = (kind) => call("live_start", { id: device.id, kind, name: device.name }).catch(failed);

  return html`<div class="wrap">
    <div class="card head" style=${device.online ? "--tint:var(--accent-soft);background-image:linear-gradient(var(--accent-soft),var(--accent-soft))" : ""}>
      <${DeviceGlyph} device=${device} size=${72} />
      <div class="info grow">
        <div class="title ellipsis">${device.name}</div>
        <div class="chips">${deviceChips(device)}</div>
        ${!device.online && html`<div class="small muted">${t("reconnecting")}</div>`}
      </div>
      ${battery && html`<${BatteryRing} battery=${battery} size=${72} />`}
    </div>

    <${Offers} device=${device} />

    <div class="actions">
      <button class="btn accent" disabled=${!device.online} onClick=${send}><${Icon} name="send" size=${17} />${t("send_files")}</button>
      <button class="btn" disabled=${!device.online} onClick=${clipboard}><${Icon} name="clipboard" size=${17} />${t("send_clipboard")}</button>
      ${device.platform === "android" && html`<button class="btn" disabled=${!device.online || !hasCap("screen.host")} title=${hasCap("screen.host") ? "" : t("live_update_phone")} onClick=${() => show("screen")}>
        <${Icon} name="device-mobile" size=${17} />${t("live_show_screen")}</button>`}
      ${device.platform === "android" && html`<button class="btn" disabled=${!device.online || !hasCap("camera.host")} title=${hasCap("camera.host") ? t("live_camera_tip") : t("live_update_phone")} onClick=${() => show("camera")}>
        <${Icon} name="camera" size=${17} />${t("live_show_camera")}</button>`}
      ${hasCap("files") && html`<button class="btn" disabled=${!device.online} onClick=${() => set({ page: "files", selected: device.id })}>
        <${Icon} name="folder-open" size=${17} />${t("browse_files")}</button>`}
      ${device.platform === "android" && html`<button class="btn" disabled=${!device.online} onClick=${ring}>
        <${Icon} name=${ringing ? "bell-off" : "bell-ringing"} size=${17} />${ringing ? t("stop_ringing") : t("find_phone")}</button>`}
    </div>

    <div class=${"drop" + (state.dropping ? " over" : "")}>
      <${Icon} name="upload" size=${22} /><div style="font-weight:500;margin-top:4px">${t("drop_here")}</div>
      <div class="small">${t("drop_hint")}</div>
    </div>

    ${players.map((p) => html`<div class="card" key=${p.id}><${PlayerCard} device=${device} player=${p} /></div>`)}

    ${transfers.length > 0 && html`<h2>${t("recent")}</h2><div class="card flush">${transfers.map((x) => html`<${TransferRow} item=${x} key=${x.id} />`)}</div>`}

    <h2>${t("settings")}</h2>
    <div class="card flush">
      <${SettingRow} icon="clipboard" title=${t("sync_clipboard")} sub=${t("sync_clipboard_sub")} on=${device.clipboard} onChange=${(v) => setting({ clipboard: v })} />
      <${SettingRow} icon="bell" title=${t("show_its_notifications")} sub=${t("show_its_notifications_sub")} on=${device.notifications} onChange=${(v) => setting({ notifications: v })} />
      <${SettingRow} icon="download" title=${t("accept_automatically")} sub=${t("accept_automatically_sub")} on=${device.autoAccept} onChange=${(v) => setting({ autoAccept: v })} />
      <${SettingRow} icon="trash" title=${t("remove_device")} sub=${t("remove_device_sub")}>
        <button class="btn danger small" onClick=${() => set({ dialog: { kind: "remove", id: device.id } })}>${t("remove")}</button>
      <//>
    </div>
  </div>`;
}

// ---- Shared, notifications, settings --------------------------------------------------

// ---- Clipboard history ------------------------------------------------------------------

export function ClipboardPage() {
  const [query, setQuery] = useState("");
  const [items, setItems] = useState([]);
  const load = () => call("clip_history_search", { query }).then(setItems).catch(failed);
  useEffect(() => { load(); }, [query]);
  useEffect(() => { let off; listen("clip-history", load).then((f) => { off = f; }); return () => off && off(); }, [query]);
  const ago = (ms) => {
    const minutes = Math.max(0, Math.round((Date.now() - ms) / 60000));
    if (minutes < 1) return t("just_now");
    if (minutes < 60) return t("minutes_ago", minutes);
    const hours = Math.round(minutes / 60);
    return hours < 24 ? t("hours_ago", hours) : t("days_ago", Math.round(hours / 24));
  };
  const copy = async (item) => { try { await call("clip_history_copy", { id: item.id }); say(t("copied")); } catch (e) { failed(e); } };
  return html`<div class="wrap">
    <div style="display:flex;align-items:flex-end;gap:12px">
      <div class="grow"><h1>${t("title_clipboard")}</h1><p class="lead">${t("lead_clipboard")}</p></div>
      ${items.length > 0 && html`<button class="btn" onClick=${() => call("clip_history_clear", { keepPinned: true })}><${Icon} name="trash" size=${17} />${t("clear_all_but_kept")}</button>`}
    </div>
    <input type="search" placeholder=${t("search")} value=${query} onInput=${(e) => setQuery(e.target.value)}
      style="margin:10px 0;width:100%;box-sizing:border-box;padding:9px 16px;border-radius:999px;border:1px solid rgba(128,128,128,0.35);background:transparent;color:inherit;font:inherit;outline:none" />
    ${items.length === 0
      ? html`<div class="card empty"><${Icon} name="clipboard" size=${34} /><div>${query ? t("nothing_matches") : t("nothing_yet")}</div></div>`
      : html`<div class="card flush">${items.map((item) => html`<div class="item" key=${item.id}>
          <div class="grow" style="cursor:pointer;min-width:0" onClick=${() => copy(item)}>
            <div style="white-space:pre-wrap;word-break:break-word;max-height:4.6em;overflow:hidden">${item.text}</div>
            <div class="small muted">${[item.from, ago(item.at_ms)].filter(Boolean).join(" · ")}</div>
          </div>
          <button class=${"btn small" + (item.pinned ? " accent" : "")} title=${item.pinned ? t("let_go") : t("keep_this")} onClick=${() => call("clip_history_pin", { id: item.id })}><${Icon} name="pin" size=${15} /></button>
          <button class="btn small" title=${t("remove")} onClick=${() => call("clip_history_remove", { id: item.id })}><${Icon} name="x" size=${15} /></button>
        </div>`)}</div>`}
  </div>`;
}

// ---- The files of another device ----------------------------------------------------------

const KINDS = {
  all: () => true,
  folders: (e) => e.dir,
  images: (e) => !e.dir && /\.(jpe?g|png|gif|webp|heic|bmp|svg)$/i.test(e.name),
  video: (e) => !e.dir && /\.(mp4|mov|mkv|avi|webm|m4v)$/i.test(e.name),
  documents: (e) => !e.dir && /\.(pdf|docx?|xlsx?|pptx?|txt|md|rtf|odt|csv)$/i.test(e.name),
};

function sizeText(n) {
  if (n < 1024) return n + " B";
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return v.toFixed(v < 10 ? 1 : 0) + " " + units[i];
}

export function FilesPage() {
  const device = state.devices.find((d) => d.id === state.selected);
  const [path, setPath] = useState("/");
  const [entries, setEntries] = useState([]);
  const [problem, setProblem] = useState("");
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState("all");
  const [picked, setPicked] = useState(new Set());
  const [busy, setBusy] = useState(false);
  if (!device) return html`<div class="wrap"><div class="card empty">${t("nothing_yet")}</div></div>`;

  const open = async (next) => {
    setLoading(true); setProblem("");
    try {
      let target = next;
      if (target === "/") {
        const roots = await call("fs_roots", { id: device.id });
        // With one shared folder there is nothing to choose: look inside it.
        if (roots.length === 1) target = "/" + roots[0].name;
        else { setEntries(roots.map((r) => ({ name: r.name, dir: true, size: 0, modifiedMs: 0, readonly: !r.write }))); setPath("/"); setPicked(new Set()); setLoading(false); return; }
      }
      setEntries(await call("fs_list", { id: device.id, path: target }));
      setPath(target); setPicked(new Set()); setQuery("");
    } catch (e) { setProblem(String(e)); }
    setLoading(false);
  };
  useEffect(() => { open("/"); }, [device.id]);

  const parts = path.split("/").filter(Boolean);
  const join = (name) => (path === "/" ? "" : path) + "/" + name;
  const shown = entries.filter((e) => KINDS[kind](e) && query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => e.name.toLowerCase().includes(w)));
  const toggle = (name) => setPicked((old) => { const next = new Set(old); next.has(name) ? next.delete(name) : next.add(name); return next; });
  const chosen = entries.filter((e) => picked.has(e.name));
  const download = async () => {
    setBusy(true);
    try {
      const result = await call("fs_get", { id: device.id, paths: chosen.map((e) => join(e.name)), folders: chosen.map((e) => e.dir) });
      say(t("downloaded_n", result.saved.length) + (result.skippedFolders ? " " + t("folders_skipped") : ""));
      setPicked(new Set());
    } catch (e) { failed(e); }
    setBusy(false);
  };

  return html`<div class="wrap">
    <div style="display:flex;align-items:center;gap:10px">
      <button class="btn small" onClick=${() => set({ page: "device" })}><${Icon} name="chevron-left" size=${16} />${t("back")}</button>
      <div class="grow"><h1 style="margin:0">${t("files_on", device.name)}</h1></div>
      ${chosen.length > 0 && html`<button class="btn accent" disabled=${busy} onClick=${download}><${Icon} name="download" size=${17} />${t("download_n", chosen.length)}</button>`}
    </div>
    <div class="small" style="margin:8px 0;display:flex;gap:4px;flex-wrap:wrap;align-items:center">
      <a href="#" onClick=${(e) => { e.preventDefault(); open("/"); }}>${t("folders")}</a>
      ${parts.map((part, i) => html`<span class="muted">/</span><a href="#" onClick=${(e) => { e.preventDefault(); open("/" + parts.slice(0, i + 1).join("/")); }}>${part}</a>`)}
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px">
      <input type="search" placeholder=${t("search_folder")} value=${query} onInput=${(e) => setQuery(e.target.value)}
        style="flex:1;min-width:160px;padding:9px 16px;border-radius:999px;border:1px solid rgba(128,128,128,0.35);background:transparent;color:inherit;font:inherit;outline:none" />
      ${Object.keys(KINDS).map((k) => html`<button class=${"btn small" + (kind === k ? " accent" : "")} onClick=${() => setKind(k)}>${t("kind_" + k)}</button>`)}
    </div>
    ${problem && html`<div class="card"><div class="small" style="color:var(--danger,#d33)">${problem}</div></div>`}
    ${loading ? html`<div class="card empty">${t("looking")}</div>`
      : shown.length === 0 ? html`<div class="card empty"><${Icon} name="files" size=${34} /><div>${entries.length ? t("nothing_matches") : t("folder_empty")}</div></div>`
      : html`<div class="card flush">${shown.map((e) => html`<div class="item" key=${e.name}>
          <button class="btn small" style=${"width:30px;height:30px;padding:0;border-radius:999px;justify-content:center;" + (picked.has(e.name) ? "background:var(--accent, #5b5bd6);color:#fff;border-color:transparent" : "")} title=${t("select")} onClick=${() => toggle(e.name)}>
            ${picked.has(e.name) ? html`<${Icon} name="check" size=${15} />` : html`<${Icon} name=${e.dir ? "folder-open" : "file"} size=${15} />`}
          </button>
          <div class="grow" style="cursor:pointer;min-width:0" onClick=${() => (e.dir ? open(join(e.name)) : toggle(e.name))}>
            <div class="ellipsis">${e.name}</div>
            <div class="small muted">${e.dir ? t("folder") : sizeText(e.size)}${e.modifiedMs ? " · " + new Date(e.modifiedMs).toLocaleDateString() : ""}</div>
          </div>
        </div>`)}</div>`}
  </div>`;
}

export function SharedPage() {
  return html`<div class="wrap">
    <div style="display:flex;align-items:flex-end;gap:12px">
      <div class="grow"><h1>${t("title_shared")}</h1><p class="lead">${t("lead_shared")}</p></div>
      <button class="btn" onClick=${() => call("open_downloads").catch(failed)}><${Icon} name="folder-open" size=${17} />${t("open_folder")}</button>
    </div>
    ${state.transfers.length === 0
      ? html`<div class="card empty"><${Icon} name="files" size=${34} /><div>${t("nothing_yet")}</div></div>`
      : html`<div class="card flush">${state.transfers.map((x) => html`<${TransferRow} item=${x} key=${x.id} />`)}</div>`}
  </div>`;
}

export function NotificationsPage() {
  const list = state.notifications;
  return html`<div class="wrap">
    <div style="display:flex;align-items:flex-end;gap:12px">
      <div class="grow"><h1>${t("title_notifications")}</h1><p class="lead">${t("lead_notifications")}</p></div>
      ${list.length > 0 && html`<button class="btn" onClick=${() => { call("clear_notifications"); set({ notifications: [] }); }}><${Icon} name="trash" size=${17} />${t("clear_all")}</button>`}
    </div>
    ${list.length === 0
      ? html`<div class="card empty"><${Icon} name="bell" size=${34} /><div style="font-weight:600">${t("no_notifications")}</div><div class="small">${t("no_notifications_sub")}</div></div>`
      : html`<div class="card flush">${list.map((n) => html`<div class="item" key=${n.device + n.key}>
          <span class="glyph on" style="width:34px;height:34px"><${Icon} name="bell" size=${16} /></span>
          <div class="grow">
            <div style="display:flex;gap:8px"><span class="small muted" style="font-weight:600">${n.appName}</span><span class="grow"></span><span class="small faint">${ago(n.ts)}</span></div>
            ${n.title && html`<div style="font-weight:600">${n.title}</div>`}
            ${n.text && html`<div class="muted">${n.text}</div>`}
            <div class="small faint">${n.deviceName}</div>
          </div>
        </div>`)}</div>`}
  </div>`;
}

/** One line for the settings row: where the update stands. */
function updateText() {
  const u = state.update;
  switch (u.state) {
    case "checking": return t("update_checking");
    case "up-to-date": return t("update_up_to_date");
    case "available": return t("update_available", u.version);
    case "downloading": return t("update_downloading", u.version);
    case "installing": return t("update_installing");
    case "failed": return u.reason;
    default: return t("update_version_now", state.version);
  }
}

// This PC as the main computer: the mouse and keyboard go on to the computer that sits next to it.
function SharedPointerSettings({ s, patch }) {
  const others = state.devices.filter((d) => d.online && (d.platform === "macos" || d.platform === "windows"));
  const edges = ["left", "right", "top", "bottom"];
  return html`<div>
    <h2>${t("share_pointer")}</h2>
    <div class="card flush">
      <${SettingRow} icon="pointer" title=${t("share_pointer_next")} sub=${t("share_pointer_sub")}>
        <select value=${s.shareDevice} onChange=${(e) => patch({ shareDevice: e.target.value, shareEdge: e.target.value && !s.shareEdge ? "right" : s.shareEdge })}>
          <option value="">${t("share_pointer_off")}</option>
          ${others.map((d) => html`<option value=${d.id}>${d.name}</option>`)}
        </select>
      <//>
      ${s.shareDevice && html`<${SettingRow} icon="devices" title=${t("share_pointer_side")} sub=${t("share_pointer_release")}>
        <select value=${s.shareEdge} onChange=${(e) => patch({ shareEdge: e.target.value })}>
          ${edges.map((e) => html`<option value=${e}>${t("edge_" + e)}</option>`)}
        </select>
      <//>`}
    </div>
  </div>`;
}

export function SettingsPage() {
  const s = state.settings;
  const [name, setName] = useState(state.self ? state.self.name : "");
  const patch = async (change) => { set({ settings: await call("set_settings", { patch: change }) }); setLanguage(state.settings.language, state.systemLanguage); };
  return html`<div class="wrap">
    <div><h1>${t("settings")}</h1></div>

    <h2>${t("this_pc")}</h2>
    <div class="card" style="display:flex;gap:10px;align-items:center">
      <label class="muted" for="pcname">${t("pc_name")}</label>
      <input id="pcname" type="text" class="grow" value=${name} onInput=${(e) => setName(e.target.value)} />
      <button class="btn" disabled=${!name.trim() || (state.self && name === state.self.name)} onClick=${() => call("rename_self", { name: name.trim() }).then(() => set({ self: { ...state.self, name: name.trim() } })).catch(failed)}>${t("save")}</button>
    </div>

    <div class="card flush">
      <${SettingRow} icon="download" title=${t("download_folder")} sub=${state.downloadDir}>
        <div style="display:flex;gap:6px">
          <button class="btn small" onClick=${async () => { const dir = await call("choose_download_dir"); if (dir) set({ downloadDir: dir }); }}>${t("change")}</button>
          <button class="btn small" onClick=${() => call("open_downloads").catch(failed)}>${t("open_folder")}</button>
        </div>
      <//>
      <${SettingRow} icon="power" title=${t("start_with_windows")} sub=${t("start_with_windows_sub")} on=${state.autostart}
        onChange=${async (v) => { try { set({ autostart: await call("set_autostart", { enabled: v }) }); } catch (e) { failed(e); } }} />
      <${SettingRow} icon="x" title=${t("close_to_tray")} sub=${t("close_to_tray_sub")} on=${s.closeToTray} onChange=${(v) => patch({ closeToTray: v })} />
      <${SettingRow} icon="clipboard" title=${t("copy_codes")} sub=${t("copy_codes_sub")} on=${s.copyCodes} onChange=${(v) => patch({ copyCodes: v })} />
      <${SettingRow} icon="music" title=${t("system_media")} sub=${t("system_media_sub")} on=${s.systemMedia} onChange=${(v) => patch({ systemMedia: v })} />
      <${SettingRow} icon="pointer" title=${t("remote_input")} sub=${t("remote_input_sub")} on=${s.remoteInput} onChange=${(v) => patch({ remoteInput: v })} />
      <${SettingRow} icon="bell" title=${t("phone_notifications")} sub=${t("phone_notifications_sub")} on=${s.phoneNotifications} onChange=${(v) => patch({ phoneNotifications: v })} />
      <${SettingRow} icon="refresh" title=${t("auto_update")} sub=${t("auto_update_sub")} on=${s.autoUpdate} onChange=${(v) => patch({ autoUpdate: v })} />
      <${SettingRow} icon="arrow-down" title=${t("check_now")} sub=${updateText()}>
        <button class="btn small" disabled=${state.update.state === "checking" || state.update.state === "downloading" || state.update.state === "installing"} onClick=${() => call("check_update").then((u) => set({ update: { dismissed: state.update.dismissed, ...u } })).catch(failed)}>${t("check_now")}</button>
      <//>
      <${SettingRow} icon="info-circle" title=${t("language")}>
        <select value=${s.language} onChange=${(e) => patch({ language: e.target.value })}>
          <option value="auto">${t("language_auto")}</option><option value="en">English</option><option value="nl">Nederlands</option>
        </select>
      <//>
    </div>

    ${state.canShare && html`<${SharedPointerSettings} s=${s} patch=${patch} />`}

    <h2>${t("about")}</h2>
    <div class="card">
      <div>Tandem ${state.version}${state.build ? " (" + state.build + ")" : ""}</div>
      <div class="small muted" style="margin-top:6px">${t("experimental_note")}</div>
      <div class="small muted" style="margin-top:6px">${t("firewall")}</div>
      <div style="margin-top:10px"><button class="btn small" onClick=${() => call("open_logs").catch(failed)}>${t("open_logs")}</button></div>
    </div>
  </div>`;
}

export { language };
