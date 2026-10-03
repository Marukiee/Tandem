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
      <${SettingRow} icon="bell" title=${t("phone_notifications")} sub=${t("phone_notifications_sub")} on=${s.phoneNotifications} onChange=${(v) => patch({ phoneNotifications: v })} />
      <${SettingRow} icon="info-circle" title=${t("language")}>
        <select value=${s.language} onChange=${(e) => patch({ language: e.target.value })}>
          <option value="auto">${t("language_auto")}</option><option value="en">English</option><option value="nl">Nederlands</option>
        </select>
      <//>
    </div>

    <h2>${t("about")}</h2>
    <div class="card">
      <div>Tandem ${state.version}${state.build ? " (" + state.build + ")" : ""}</div>
      <div class="small muted" style="margin-top:6px">${t("firewall")}</div>
    </div>
  </div>`;
}

export { language };
