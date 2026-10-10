// The pages of the main window.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call, listen, native } from "./backend.js";
import { ArrangementEditor } from "./arrange.js";
import { BatteryRing, Chip, DeviceGlyph, PlayerCard, Switch, ago, deviceChips, fmtSize, platformIcon } from "./components.js";
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
      ${offer && offer.code && html`<div class="typecode"><div class="muted small">${t("or_type_code")}</div><div class="digits">${offer.code}</div></div>`}
      <div style="display:flex;gap:8px">
        <input type="text" class="grow" readonly value=${offer ? offer.uri : ""} onFocus=${(e) => e.target.select()} />
        <button class="btn" onClick=${copy}><${Icon} name=${copied ? "check" : "copy"} size=${16} />${copied ? t("copied") : t("copy_link")}</button>
      </div>
      <div style="margin-top:10px"><button class="btn small" onClick=${fresh}><${Icon} name="refresh" size=${14} />${t("new_code")}</button></div>`}
    ${tab === "enter" && html`
      <label class="muted small" for="link">${t("paste_label")}</label>
      <div style="display:flex;gap:8px;margin-top:6px">
        <input id="link" type="text" class="grow" placeholder=${t("code_placeholder")} value=${link} onInput=${(e) => setLink(e.target.value)} />
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

/** [disabled] greys the row out, and [why] says in its place what has to be done before it works. */
function SettingRow({ icon, title, sub, on, onChange, children, disabled, why }) {
  return html`<div class=${"setting" + (disabled ? " disabled" : "")}>
    <${Icon} name=${icon} size=${20} />
    <div class="grow"><div class="t">${title}</div>${(why || sub) && html`<div class="s">${why || sub}</div>`}</div>
    ${children || html`<${Switch} on=${on && !disabled} onChange=${onChange} label=${title} disabled=${disabled} />`}
  </div>`;
}

/** Why this system will not let Tandem drive the pointer and keyboard, in words, or nothing when it does. */
function inputWhy() {
  const input = state.input;
  if (input.ok) return "";
  return t(input.why === "no-display" ? "input_no_display" : "input_wayland");
}

// Whether this device may look at the screen of this computer, and use its mouse and keyboard: ask every time, always, or never.
function ScreenPolicyRows({ device }) {
  const [policy, setPolicy] = useState(null);
  useEffect(() => { call("media_policy", { id: device.id }).then(setPolicy).catch(() => {}); }, [device.id]);
  if (!policy || !state.canHost) return null;
  const change = (patch) => call("media_policy_set", { id: device.id, ...patch }).then(setPolicy).catch(failed);
  const choices = (value, onChange) => html`<select value=${value} onChange=${(e) => onChange(e.target.value)}>
    <option value="ask">${t("policy_ask")}</option><option value="always">${t("policy_always")}</option><option value="never">${t("policy_never")}</option></select>`;
  return html`<${SettingRow} icon="device-desktop" title=${t("policy_screen")} sub=${t("policy_screen_sub")}>${choices(policy.screen, (v) => change({ screen: v }))}<//>
    <${SettingRow} icon="pointer" title=${t("policy_control")} sub=${t("policy_control_sub")}>${choices(policy.control, (v) => change({ control: v }))}<//>`;
}

/** The name of a device over its two rows. Nothing at all until the rows are there, so no empty card shows while they load. */
function PolicyBlock({ device }) {
  const [policy, setPolicy] = useState(null);
  useEffect(() => { call("media_policy", { id: device.id }).then(setPolicy).catch(() => {}); }, [device.id]);
  if (!policy) return null;
  return html`<div style="display:flex;flex-direction:column;gap:6px">
    <div class="small muted" style="padding-left:4px">${device.name}</div>
    <div class="card flush"><${ScreenPolicyRows} device=${device} /></div>
  </div>`;
}

/** The last answer about the ssh port of each computer, so a page that is opened again shows it at once. */
const sshSeen = new Map();

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
  const isComputerEarly = device.platform === "macos" || device.platform === "windows" || device.platform === "linux";
  const show = (kind) => call("live_start", { id: device.id, kind, name: device.name, computer: isComputerEarly }).catch(failed);
  // A computer that answers on the ssh port can be logged in to from here. The button is grey, with the reason, when it does not.
  const isComputer = device.platform === "macos" || device.platform === "windows" || device.platform === "linux";
  // What was found the last time is shown at once, and looked at again quietly: a button that starts grey and lights up a moment later
  // reads as broken. Until it has been looked at once it is taken to be there, and a press finds the address itself.
  const [sshAddress, setSshAddress] = useState(() => sshSeen.get(device.id) || null);
  const [sshChecked, setSshChecked] = useState(() => sshSeen.has(device.id));
  useEffect(() => {
    let current = true;
    if (isComputer && device.online) {
      call("ssh_probe", { id: device.id }).then((address) => { sshSeen.set(device.id, address || null); if (current) { setSshAddress(address || null); setSshChecked(true); } }).catch(() => { if (current) setSshChecked(true); });
    } else {
      setSshAddress(null);
      setSshChecked(true);
    }
    return () => { current = false; };
  }, [device.id, device.online]);
  const sshReady = device.online && (sshAddress || !sshChecked);
  const openSsh = async () => {
    const address = sshAddress || (await call("ssh_probe", { id: device.id }).catch(() => null));
    if (!address) { setSshChecked(true); return say(sshReason); }
    call("ssh_open", { id: device.id, name: device.name, address }).catch(failed);
  };
  const sshReason = !device.online ? t("ssh_why_offline") : t("ssh_why_" + device.platform);

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

    <div class="agroup">
      <div class="agroup-title">${t("group_share")}</div>
      <div class="actions">
        <button class="btn" disabled=${!device.online} onClick=${clipboard}><${Icon} name="clipboard" size=${17} />${t("send_clipboard")}</button>
        ${hasCap("files") && html`<button class="btn" disabled=${!device.online} onClick=${() => set({ page: "files", selected: device.id })}>
          <${Icon} name="folder-open" size=${17} />${t("browse_files")}</button>`}
      </div>
    </div>
    ${(isComputer || device.platform === "android") && html`<div class="agroup">
      <div class="agroup-title">${t(isComputer ? "group_use_computer" : "group_on_phone")}</div>
      <div class="actions">
        ${device.platform === "android" && html`<button class="btn" disabled=${!device.online || !hasCap("screen.host")} title=${hasCap("screen.host") ? "" : t("live_update_phone")} onClick=${() => show("screen")}>
          <${Icon} name="device-mobile" size=${17} />${t("live_show_screen")}</button>`}
        ${device.platform === "android" && html`<button class="btn" disabled=${!device.online || !hasCap("camera.host")} title=${hasCap("camera.host") ? t("live_camera_tip") : t("live_update_phone")} onClick=${() => show("camera")}>
          <${Icon} name="camera" size=${17} />${t("live_show_camera")}</button>`}
        ${isComputer && hasCap("screen.host") && html`<button class="btn" disabled=${!device.online} title=${t("host_view_tip")} onClick=${() => show("screen")}>
          <${Icon} name="device-desktop" size=${17} />${t("host_view")}</button>`}
        ${isComputer && html`<button class="btn" disabled=${!sshReady} title=${sshReady ? t("ssh_title") : sshReason} onClick=${openSsh}>
          <${Icon} name="terminal" size=${17} />${t("ssh_terminal")}</button>`}
        ${device.platform === "android" && hasCap("capture") && html`<button class="btn" disabled=${!device.online} title=${t("insert_photo_tip")} onClick=${() => call("capture_request", { id: device.id, kind: "photo" }).catch(failed)}>
          <${Icon} name="camera" size=${17} />${t("insert_photo")}</button>
        <button class="btn" disabled=${!device.online} title=${t("insert_scan_tip")} onClick=${() => call("capture_request", { id: device.id, kind: "document" }).catch(failed)}>
          <${Icon} name="file" size=${17} />${t("insert_scan")}</button>`}
        ${device.platform === "android" && html`<button class="btn" disabled=${!device.online} onClick=${ring}>
          <${Icon} name=${ringing ? "bell-off" : "bell-ringing"} size=${17} />${ringing ? t("stop_ringing") : t("find_phone")}</button>`}
      </div>
    </div>`}

    ${isComputer && sshChecked && !sshAddress && html`<div class="small muted" style="margin:-4px 2px 0">${sshReason}</div>`}

    <div class=${"drop" + (state.dropping ? " over" : "") + (device.online ? " click" : " off")} role="button" tabindex="0"
      title=${t("drop_click")} onClick=${() => device.online && send()}
      onKeyDown=${(e) => { if ((e.key === "Enter" || e.key === " ") && device.online) { e.preventDefault(); send(); } }}>
      <${Icon} name="upload" size=${24} /><div class="drop-title">${t("drop_here")}</div>
      <div class="small">${t("drop_hint")}</div>
    </div>

    ${players.map((p) => html`<div class="card" key=${p.id}><${PlayerCard} device=${device} player=${p} /></div>`)}

    ${transfers.length > 0 && html`<h2>${t("recent")}</h2><div class="card flush">${transfers.map((x) => html`<${TransferRow} item=${x} key=${x.id} />`)}</div>`}

    <h2>${t("settings")}</h2>
    <div class="card flush">
      <${SettingRow} icon="clipboard" title=${t("sync_clipboard")} sub=${t("sync_clipboard_sub")} on=${device.clipboard} onChange=${(v) => setting({ clipboard: v })} />
      <${SettingRow} icon="bell" title=${t("show_its_notifications")} sub=${t("show_its_notifications_sub")} on=${device.notifications} onChange=${(v) => setting({ notifications: v })} />
      ${hasCap("screen.view") && html`<${ScreenPolicyRows} device=${device} />`}
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
  // The items that are shown in full: a long text is cut after a few lines until it is opened.
  const isLong = (text) => text.length > 160 || text.split("\n").length > 4;
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
            <div class=${"cliptext" + (isLong(item.text) ? " long" : "")} title=${isLong(item.text) ? t("drag_to_enlarge") : ""} onClick=${(e) => { if (window.getSelection().toString()) e.stopPropagation(); }}>${item.text}</div>
            <div class="small muted">${[item.from, ago(item.at_ms), isLong(item.text) ? t("n_characters", item.text.length) : ""].filter(Boolean).join(" · ")}</div>
          </div>
          <div class="item-actions">
            <button class="iconbtn" title=${t("copy_again")} onClick=${() => copy(item)}><${Icon} name="copy" size=${16} /></button>
            <button class=${"iconbtn" + (item.pinned ? " on" : "")} title=${item.pinned ? t("let_go") : t("keep_this")} onClick=${() => call("clip_history_pin", { id: item.id })}><${Icon} name="pin" size=${16} /></button>
            <button class="iconbtn danger" title=${t("remove")} onClick=${() => call("clip_history_remove", { id: item.id })}><${Icon} name="x" size=${16} /></button>
          </div>
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

// What a file is, by the end of its name.
const SHARED_KINDS = {
  pictures: /\.(jpe?g|png|gif|heic|heif|webp|bmp|tiff?|svg|raw|dng|cr2|nef|arw)$/i,
  videos: /\.(mp4|mov|mkv|avi|webm|m4v|3gp|mts)$/i,
  audio: /\.(mp3|m4a|wav|flac|aac|ogg|opus|aiff)$/i,
  documents: /\.(pdf|docx?|xlsx?|pptx?|txt|md|rtf|odt|ods|odp|csv|pages|numbers|key|epub)$/i,
  archives: /\.(zip|rar|7z|tar|gz|tgz|bz2|xz|dmg|iso|apk|pkg|deb|rpm|appimage)$/i,
};
const kindOf = (name) => Object.keys(SHARED_KINDS).find((k) => SHARED_KINDS[k].test(name)) || "other";

/** A small button that opens a list to choose from. It is coloured while something other than "all" is chosen. */
function FilterChip({ icon, label, active, options, value, onPick }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return undefined;
    const away = () => setOpen(false);
    // Closed by a press anywhere else; the press on the chip itself is dealt with by the chip.
    setTimeout(() => document.addEventListener("click", away), 0);
    return () => document.removeEventListener("click", away);
  }, [open]);
  return html`<div class="chipwrap">
    <button class=${"chip" + (active ? " on" : "")} onClick=${(e) => { e.stopPropagation(); setOpen(!open); }}>
      <${Icon} name=${icon} size=${15} /><span>${label}</span><${Icon} name="arrow-down" size=${13} />
    </button>
    ${open && html`<div class="chipmenu">
      ${options.map((o) => html`<button class=${"chipitem" + (o.key === value ? " on" : "")} onClick=${() => { onPick(o.key); setOpen(false); }}>
        <span class="grow">${o.label}</span>${o.key === value && html`<${Icon} name="check" size=${15} />`}
      </button>`)}
    </div>`}
  </div>`;
}

export function SharedPage() {
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState("all");
  const [direction, setDirection] = useState("all");
  const [peer, setPeer] = useState("all");
  const [period, setPeriod] = useState("always");
  const [failed, setFailed] = useState(false);
  const day = 86400000;
  const within = { today: day, week: 7 * day, month: 30 * day };
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const items = state.transfers.filter((x) =>
    (kind === "all" || kindOf(x.name) === kind)
    && (direction === "all" || (direction === "received") === !!x.incoming)
    && (peer === "all" || x.peer === peer)
    && (period === "always" || Date.now() - (x.startedAt || 0) < within[period])
    && (!failed || x.state === "failed")
    && words.every((w) => x.name.toLowerCase().includes(w)));
  const filtering = kind !== "all" || direction !== "all" || peer !== "all" || period !== "always" || failed || query;
  const peers = [...new Set(state.transfers.map((x) => x.peer))].map((id) => ({ key: id, label: (state.devices.find((d) => d.id === id) || {}).name || "?" }));
  const clear = () => { setQuery(""); setKind("all"); setDirection("all"); setPeer("all"); setPeriod("always"); setFailed(false); };
  const kinds = ["all", "pictures", "videos", "audio", "documents", "archives", "other"].map((k) => ({ key: k, label: t("kind_" + k) }));
  const directions = ["all", "received", "sent"].map((k) => ({ key: k, label: t("dir_" + k) }));
  const periods = ["always", "today", "week", "month"].map((k) => ({ key: k, label: t("period_" + k) }));
  const nameOf = (list, key, fallback) => (list.find((o) => o.key === key) || { label: fallback }).label;
  return html`<div class="wrap">
    <div style="display:flex;align-items:flex-end;gap:12px">
      <div class="grow"><h1>${t("title_shared")}</h1><p class="lead">${t("lead_shared")}</p></div>
      <button class="btn" onClick=${() => call("open_downloads").catch(failed)}><${Icon} name="folder-open" size=${17} />${t("open_folder")}</button>
    </div>
    ${state.transfers.length === 0
      ? html`<div class="card empty"><${Icon} name="files" size=${34} /><div>${t("nothing_yet")}</div></div>`
      : html`
        <input type="search" placeholder=${t("search_name")} value=${query} onInput=${(e) => setQuery(e.target.value)}
          style="margin:10px 0 8px;width:100%;box-sizing:border-box;padding:9px 16px;border-radius:999px;border:1px solid rgba(128,128,128,0.35);background:transparent;color:inherit;font:inherit;outline:none" />
        <div class="chips">
          <${FilterChip} icon="files" label=${nameOf(kinds, kind, t("filter_type"))} active=${kind !== "all"} options=${kinds} value=${kind} onPick=${setKind} />
          <${FilterChip} icon="devices" label=${peer === "all" ? t("filter_device") : nameOf(peers, peer, "?")} active=${peer !== "all"}
            options=${[{ key: "all", label: t("all_devices") }, ...peers]} value=${peer} onPick=${setPeer} />
          <${FilterChip} icon="arrow-down" label=${direction === "all" ? t("filter_direction") : nameOf(directions, direction, "")} active=${direction !== "all"} options=${directions} value=${direction} onPick=${setDirection} />
          <${FilterChip} icon="hourglass" label=${period === "always" ? t("filter_when") : nameOf(periods, period, "")} active=${period !== "always"} options=${periods} value=${period} onPick=${setPeriod} />
          <button class=${"chip" + (failed ? " on" : "")} onClick=${() => setFailed(!failed)}><${Icon} name="alert-triangle" size=${15} /><span>${t("failed")}</span></button>
          ${filtering && html`<button class="chip" onClick=${clear}><${Icon} name="x" size=${15} /><span>${t("clear_filters")}</span></button>`}
        </div>
        ${items.length === 0
          ? html`<div class="card empty"><${Icon} name="files" size=${34} /><div>${t("nothing_matches")}</div></div>`
          : html`<div class="card flush">${items.map((x) => html`<${TransferRow} item=${x} key=${x.id} />`)}</div>`}`}
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

// Which devices may use the mouse and keyboard of this PC: all of them until the person leaves one out.
function AllowedDevices({ s, patch }) {
  const people = state.devices.filter((d) => d.platform === "macos" || d.platform === "windows" || d.platform === "linux" || d.platform === "android");
  if (people.length === 0) return null;
  const listed = Array.isArray(s.pointerAllowed) ? s.pointerAllowed : null;
  const isOn = (d) => listed === null || listed.includes(d.id);
  const toggle = (d, on) => {
    const base = listed === null ? people.map((p) => p.id) : listed;
    patch({ pointerAllowed: on ? [...new Set([...base, d.id])] : base.filter((id) => id !== d.id) });
  };
  return html`<div style="display:flex;flex-direction:column;gap:8px">
    <div class="small muted">${t("mouse_who")}</div>
    <div class="card flush">
      ${people.map((d) => html`<${SettingRow} icon=${platformIcon(d.platform)} title=${d.name} sub=${d.online ? t("connected") : t("not_connected")} on=${isOn(d)} onChange=${(v) => toggle(d, v)} />`)}
    </div>
  </div>`;
}

// One mouse and keyboard for more computers, in the settings: how it works, where each computer sits, and who may use this PC.
function MouseSection({ s, patch }) {
  const others = state.devices.filter((d) => d.platform === "macos" || d.platform === "windows");
  const edges = ["left", "right", "top", "bottom"];
  const step = (icon, text) => html`<div style="display:flex;gap:12px;align-items:center"><span class="glyph" style="width:30px;height:30px;flex:none"><${Icon} name=${icon} size=${16} /></span><span>${text}</span></div>`;
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <div><h2>${t("mouse_title")}</h2><div class="muted">${t("mouse_intro")}</div></div>

    <div class="card" style="display:flex;flex-direction:column;gap:12px">
      <div style="font-weight:600">${t("mouse_how")}</div>
      ${step("devices", t("mouse_step1"))}
      ${step("pointer", t("mouse_step2"))}
      ${step("refresh", t("mouse_step3"))}
    </div>

    <h2>${t("mouse_here")}</h2>
    ${state.canShare
      ? html`<${ArrangementEditor} />`
      : html`<div class="card"><div class="small muted">${t("share_pointer_linux")}</div></div>`}
    <div class="small muted">${t("mouse_other_must_allow")}</div>

    <h2>${t("mouse_in")}</h2>
    <div class="card flush">
      <${SettingRow} icon="pointer" title=${t("mouse_in_title")} sub=${t("mouse_in_sub")} on=${s.remoteInput} onChange=${(v) => patch({ remoteInput: v })} disabled=${!state.input.ok} why=${inputWhy()} />
      ${state.hasLid && html`<${SettingRow} icon="device-laptop" title=${t("lid_title")} sub=${t("lid_sub")} on=${s.keepWhenLidClosed} onChange=${(v) => patch({ keepWhenLidClosed: v })} />`}
    </div>
    ${s.remoteInput && state.input.ok && html`<${AllowedDevices} s=${s} patch=${patch} />`}
    <div class="small muted">${t("mouse_firewall")}</div>

    <h2>${t("mouse_phone")}</h2>
    <div class="card"><div class="small muted">${t("mouse_phone_text")}</div></div>
  </div>`;
}

// Quick Share in the settings: the switch, and the devices that were found, to send files or the clipboard to.
function QuickShareSection({ s, patch }) {
  const [view, setView] = useState({ enabled: s.quickShare, peers: [], outgoing: [], problem: null });
  useEffect(() => {
    call("qs_state").then(setView).catch(() => {});
    let off;
    listen("quickshare", setView).then((f) => { off = f; });
    return () => off && off();
  }, []);
  const send = (peer, command) => call(command, { peer }).catch((e) => failed(e === "no-text" ? t("qs_no_text") : e));
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("qs_title")}</h2>
    <div class="card flush">
      <${SettingRow} icon="quickshare" title=${t("qs_title")} sub=${t("qs_sub")} on=${s.quickShare} onChange=${(v) => patch({ quickShare: v })} />
    </div>
    ${view.problem && html`<div class="small" style="color:var(--danger,#c0392b)">${view.problem}</div>`}
    ${s.quickShare && html`<h3 class="muted" style="margin:6px 0 0;font-size:13px">${t("qs_nearby")}</h3>
      ${view.peers.length === 0 ? html`<div class="card small muted">${t("qs_searching")}</div>` : html`<div class="card flush">
        ${view.peers.map((peer) => html`<${SettingRow} key=${peer.id} icon=${peer.kind === "phone" ? "device-mobile" : "device-laptop"} title=${peer.name} sub="">
          <div style="display:flex;gap:6px">
            <button class="btn small" onClick=${() => send(peer.id, "qs_pick_and_send")}>${t("qs_send_files")}</button>
            <button class="btn small" onClick=${() => send(peer.id, "qs_send_clipboard")}>${t("qs_send_clipboard")}</button>
          </div>
        <//>`)}
      </div>`}
      ${view.outgoing.map((o) => html`<div class="card small" key=${o.id}>${o.state === "sending" ? t("qs_sending_to", o.peerName) + (o.pin ? " \u00b7 " + t("qs_pin_is", o.pin) : "") : o.state === "sent" ? t("qs_sent_to", o.peerName) : o.state === "refused" ? t("qs_said_no", o.peerName) : t("qs_failed")}</div>`)}`}
  </div>`;
}

// The folders of this computer that other devices may look at, and what they may do there.
function FilesHostSection() {
  const [policy, setPolicy] = useState(null);
  useEffect(() => { call("files_policy").then(setPolicy).catch(() => {}); }, []);
  const change = (patch) => call("files_update", { patch }).then(setPolicy).catch(failed);
  if (!policy) return null;
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("files_host_title")}</h2>
    <div class="card flush">
      <${SettingRow} icon="folder-open" title=${t("files_host_on")} sub=${t("files_host_on_sub")} on=${policy.enabled} onChange=${(v) => change({ enabled: v })} />
      ${policy.shares.map((share, index) => html`<${SettingRow} key=${share.path} icon="folder-open" title=${share.name} sub=${share.path}>
        <div style="display:flex;gap:10px;align-items:center">
          <label class="small muted" style="display:flex;gap:6px;align-items:center">${t("files_host_may_change")}
            <${Switch} on=${share.write} onChange=${(v) => change({ shareWrite: { index, write: v } })} label=${t("files_host_may_change")} /></label>
          <button class="btn small" onClick=${() => change({ removeShare: index })}>${t("remove")}</button>
        </div>
      <//>`)}
      <${SettingRow} icon="plus" title=${t("files_host_add")} sub="">
        <button class="btn small" onClick=${() => call("files_add_folder").then(setPolicy).catch(failed)}>${t("files_host_choose")}</button>
      <//>
      <${SettingRow} icon="trash" title=${t("files_host_delete")} sub=${t("files_host_delete_sub")} on=${policy.delete} onChange=${(v) => change({ delete: v })} disabled=${!policy.enabled} />
      <${SettingRow} icon="file" title=${t("files_host_hidden")} sub=${t("files_host_hidden_sub")} on=${policy.hidden} onChange=${(v) => change({ hidden: v })} disabled=${!policy.enabled} />
    </div>
  </div>`;
}

// The settings, in sections like the settings of the Mac: what belongs together is together, and a section is a page of its own.
const SECTIONS = [
  ["general", "tab_general"], ["clipboard", "tab_clipboard"], ["files", "tab_files"], ["remote", "tab_remote"],
  ["mouse", "tab_mouse"], ["quickshare", "tab_quickshare"], ["updates", "tab_updates"], ["about", "about"],
];

function rememberedSection() {
  try { return SECTIONS.some(([id]) => id === localStorage.getItem("settingsSection")) ? localStorage.getItem("settingsSection") : "general"; } catch { return "general"; }
}

export function SettingsPage() {
  const s = state.settings;
  const [section, setSection] = useState(rememberedSection);
  const open = (id) => { setSection(id); try { localStorage.setItem("settingsSection", id); } catch { /* the choice is only a convenience */ } };
  const patch = async (change) => { set({ settings: await call("set_settings", { patch: change }) }); setLanguage(state.settings.language, state.systemLanguage); };
  return html`<div class="wrap">
    <div><h1>${t("settings")}</h1></div>
    <div class="seg tabs">
      ${SECTIONS.map(([id, label]) => html`<button key=${id} class=${section === id ? "on" : ""} onClick=${() => open(id)}>${t(label)}</button>`)}
    </div>
    ${section === "general" && html`<${GeneralSettings} s=${s} patch=${patch} />`}
    ${section === "clipboard" && html`<${ClipboardSettings} s=${s} patch=${patch} />`}
    ${section === "files" && html`<${FilesHostSection} />`}
    ${section === "remote" && html`<${RemoteSettings} s=${s} patch=${patch} />`}
    ${section === "mouse" && html`<${MouseSection} s=${s} patch=${patch} />`}
    ${section === "quickshare" && html`<${QuickShareSection} s=${s} patch=${patch} />`}
    ${section === "updates" && html`<${UpdateSettings} s=${s} patch=${patch} />`}
    ${section === "about" && html`<${AboutSettings} />`}
  </div>`;
}

// New devices start as guests, so family and friends can join without their clipboard coming in.
function GuestRow() {
  const [on, setOn] = useState(false);
  useEffect(() => { call("guests_by_default").then(setOn).catch(() => {}); }, []);
  return html`<${SettingRow} icon="devices" title=${t("guests_title")} sub=${t("guests_sub")} on=${on}
    onChange=${(v) => call("set_guests_by_default", { on: v }).then(() => setOn(v)).catch(failed)} />`;
}

function GeneralSettings({ s, patch }) {
  const [name, setName] = useState(state.self ? state.self.name : "");
  const delays = ["low", "normal", "smooth"];
  const saved = (text) => say(text);
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("this_pc")}</h2>
    <div class="card" style="display:flex;gap:10px;align-items:center">
      <label class="muted" for="pcname">${t("pc_name")}</label>
      <input id="pcname" type="text" class="grow" value=${name} onInput=${(e) => setName(e.target.value)} />
      <button class="btn" disabled=${!name.trim() || (state.self && name === state.self.name)} onClick=${() => call("rename_self", { name: name.trim() }).then(() => set({ self: { ...state.self, name: name.trim() } })).catch(failed)}>${t("save")}</button>
    </div>
    <div class="small muted">${t("pc_name_sub")}</div>

    <h2>${t("behaviour")}</h2>
    <div class="card flush">
      <${SettingRow} icon="power" title=${t("start_with_windows")} sub=${t("start_with_windows_sub")} on=${state.autostart}
        onChange=${async (v) => { try { set({ autostart: await call("set_autostart", { enabled: v }) }); } catch (e) { failed(e); } }} />
      <${SettingRow} icon="x" title=${t("close_to_tray")} sub=${t("close_to_tray_sub")} on=${s.closeToTray} onChange=${(v) => patch({ closeToTray: v })} />
      <${SettingRow} icon="clipboard" title=${t("copy_codes")} sub=${t("copy_codes_sub")} on=${s.copyCodes} onChange=${(v) => patch({ copyCodes: v })} />
      <${SettingRow} icon="bell" title=${t("phone_notifications")} sub=${t("phone_notifications_sub")} on=${s.phoneNotifications} onChange=${(v) => patch({ phoneNotifications: v })} />
      <${SettingRow} icon="wifi" title=${t("auto_tailscale")} sub=${t("auto_tailscale_sub")} on=${s.autoTailscale} onChange=${(v) => patch({ autoTailscale: v })} />
      <${GuestRow} />
    </div>

    <h2>${t("sound_media")}</h2>
    <div class="card flush">
      <${SettingRow} icon="music" title=${t("system_media")} sub=${t("system_media_sub")} on=${s.systemMedia} onChange=${(v) => patch({ systemMedia: v })} />
      <${SettingRow} icon="volume" title=${t("phone_sound")} sub=${t("phone_sound_sub")} on=${s.phoneSound} onChange=${(v) => patch({ phoneSound: v })} />
      <${SettingRow} icon="hourglass" title=${t("sound_delay")} sub=${t("sound_delay_sub")} disabled=${!s.phoneSound}>
        <select value=${s.soundDelay || "normal"} disabled=${!s.phoneSound} onChange=${(e) => patch({ soundDelay: e.target.value })}>
          ${delays.map((d) => html`<option value=${d}>${t("delay_" + d)}</option>`)}
        </select>
      <//>
    </div>

    <h2>${t("received_files")}</h2>
    <div class="card flush">
      <${SettingRow} icon="download" title=${t("download_folder")} sub=${state.downloadDir}>
        <div style="display:flex;gap:6px">
          <button class="btn small" onClick=${async () => { const dir = await call("choose_download_dir"); if (dir) set({ downloadDir: dir }); }}>${t("change")}</button>
          <button class="btn small" onClick=${() => call("open_downloads").catch(failed)}>${t("open_folder")}</button>
        </div>
      <//>
    </div>

    <h2>${t("language")}</h2>
    <div class="card flush">
      <${SettingRow} icon="info-circle" title=${t("language")}>
        <select value=${s.language} onChange=${(e) => patch({ language: e.target.value })}>
          <option value="auto">${t("language_auto")}</option><option value="en">English</option><option value="nl">Nederlands</option>
        </select>
      <//>
    </div>

    <h2>${t("backup")}</h2>
    <div class="card flush">
      <${SettingRow} icon="upload" title=${t("backup_export")} sub=${t("backup_sub")}>
        <button class="btn small" onClick=${() => call("settings_export").then((path) => path && saved(t("backup_saved"))).catch(failed)}>${t("backup_export_button")}</button>
      <//>
      <${SettingRow} icon="refresh" title=${t("backup_import")} sub=${t("backup_import_sub")}>
        <button class="btn small" onClick=${() => call("settings_import").then((now) => { set({ settings: now }); setLanguage(now.language, state.systemLanguage); saved(t("backup_restored")); }).catch(failed)}>${t("backup_import_button")}</button>
      <//>
    </div>
  </div>`;
}

// What is kept of what was copied, for how long, and a way to empty it.
function ClipboardSettings({ s, patch }) {
  const [info, setInfo] = useState({ count: 0, pinned: 0, bytes: 0 });
  const load = () => call("clip_history_info").then(setInfo).catch(() => {});
  useEffect(() => { load(); let off; listen("clip-history", load).then((f) => { off = f; }); return () => off && off(); }, []);
  const choose = (value, list, change, label) => html`<select value=${value} onChange=${(e) => change(Number(e.target.value))}>
    ${list.map((v) => html`<option value=${v}>${label(v)}</option>`)}</select>`;
  const summary = [t("clip_items", info.count), info.pinned ? t("clip_pinned", info.pinned) : "", info.bytes ? fmtSize(info.bytes) : ""].filter(Boolean).join(", ");
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("tab_clipboard")}</h2>
    <div class="card flush">
      <${SettingRow} icon="clipboard" title=${t("clip_keep")} sub=${t("clip_keep_sub")} on=${s.clipHistory} onChange=${(v) => patch({ clipHistory: v })} />
    </div>
    <div class="small muted">${t("clip_secret_note")}</div>
    <h2>${t("clip_what_kept")}</h2>
    <div class="card flush">
      <${SettingRow} icon="files" title=${t("clip_limit")} disabled=${!s.clipHistory}>
        ${choose(s.clipLimit || 250, [100, 250, 500, 1000], (v) => patch({ clipLimit: v }), (v) => String(v))}
      <//>
      <${SettingRow} icon="hourglass" title=${t("clip_days")} disabled=${!s.clipHistory}>
        ${choose(s.clipDays || 30, [7, 30, 90, 365], (v) => patch({ clipDays: v }), (v) => t("days_" + v))}
      <//>
      <${SettingRow} icon="trash" title=${t("clip_saved")} sub=${summary || t("nothing_yet")}>
        <button class="btn small danger" disabled=${!info.count} onClick=${() => { if (confirm(t("clip_clear_confirm"))) call("clip_history_clear", { keepPinned: false }).then(load).catch(failed); }}>${t("clip_clear")}</button>
      <//>
    </div>
    <div class="small muted">${t("clip_pinned_note")}</div>
  </div>`;
}

// This computer as a screen that other devices can look at and use, who may, and what the desktop was asked.
function RemoteSettings({ s, patch }) {
  const [access, setAccess] = useState(null);
  const load = () => call("access_status").then(setAccess).catch(() => {});
  useEffect(() => { load(); }, []);
  const people = state.devices.filter((d) => d.platform === "macos" || d.platform === "windows" || d.platform === "linux" || d.platform === "android");
  const forget = () => call("portal_forget").then(() => { load(); say(t("access_forgotten")); }).catch(failed);
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("tab_remote")}</h2>
    <div class="muted">${t("remote_intro")}</div>
    <div class="card flush">
      <${SettingRow} icon="device-desktop" title=${t("remote_host")} sub=${t("remote_host_sub")} on=${s.screenHost} disabled=${!state.canHost}
        why=${!state.canHost ? t("remote_unavailable") : ""} onChange=${(v) => patch({ screenHost: v })} />
    </div>
    ${state.canHost && html`<h2>${t("remote_per_device")}</h2>`}
    ${!state.canHost ? null : people.length === 0
      ? html`<div class="card"><div class="small muted">${t("remote_none")}</div></div>`
      : people.map((d) => html`<${PolicyBlock} key=${d.id} device=${d} />`)}
    ${access && access.wayland && html`<h2>${t("access_title")}</h2>
      <div class="card flush">
        <${SettingRow} icon="pointer" title=${t("access_input")} sub=${access.inputAllowed ? "" : t("access_not_yet")}>
          <span class=${"chip " + (access.inputAllowed ? "ok" : "")}>${access.inputAllowed ? t("access_yes") : t("access_no")}</span>
        <//>
        <${SettingRow} icon="device-desktop" title=${t("access_screen")} sub=${access.screenAllowed ? "" : t("access_not_yet")}>
          <span class=${"chip " + (access.screenAllowed ? "ok" : "")}>${access.screenAllowed ? t("access_yes") : t("access_no")}</span>
        <//>
        <${SettingRow} icon="x" title=${t("access_forget")} sub=${t("access_forget_sub")}>
          <button class="btn small" disabled=${!access.inputAllowed && !access.screenAllowed} onClick=${forget}>${t("access_forget_button")}</button>
        <//>
      </div>
      <div class="small muted">${t("access_wayland_note")}</div>`}
  </div>`;
}

// Looking for a newer Tandem, and what changed in the versions that came out.
function UpdateSettings({ s, patch }) {
  const [news, setNews] = useState([]);
  useEffect(() => { call("whats_new").then(setNews).catch(() => {}); }, []);
  const lang = language() === "nl" ? "nl" : "en";
  const busy = ["checking", "downloading", "installing"].includes(state.update.state);
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("tab_updates")}</h2>
    <div class="card flush">
      <${SettingRow} icon="refresh" title=${t("auto_update")} sub=${t("auto_update_sub")} on=${s.autoUpdate} onChange=${(v) => patch({ autoUpdate: v })} />
      <${SettingRow} icon="arrow-down" title=${t("check_now")} sub=${updateText()}>
        <button class="btn small" disabled=${busy} onClick=${() => call("check_update").then((u) => set({ update: { dismissed: state.update.dismissed, ...u } })).catch(failed)}>${t("check_now")}</button>
      <//>
    </div>
    <h2>${t("whats_new")}</h2>
    ${news.length === 0
      ? html`<div class="card"><div class="small muted">${t("nothing_yet")}</div></div>`
      : news.map((entry) => { const text = entry[lang] || entry.en; return html`<div key=${entry.version} class="card" style="display:flex;flex-direction:column;gap:6px">
          <div style="display:flex;gap:8px;align-items:baseline"><b>${text.title}</b><span class="small muted">${entry.version}</span><span class="grow"></span><span class="small faint">${entry.date}</span></div>
          <ul style="margin:0;padding-left:18px">${(text.new || []).map((line) => html`<li>${line}</li>`)}</ul>
        </div>`; })}
  </div>`;
}

function AboutSettings() {
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2>${t("about")}</h2>
    <div class="card">
      <div>Tandem ${state.version}${state.build ? " (" + state.build + ")" : ""}</div>
      <div class="small muted" style="margin-top:6px">${t("experimental_note")}</div>
      <div class="small muted" style="margin-top:6px">${t("firewall")}</div>
      <div style="margin-top:10px;display:flex;gap:8px"><button class="btn small" onClick=${() => call("open_logs").catch(failed)}>${t("open_logs")}</button></div>
    </div>
  </div>`;
}

export { language };
