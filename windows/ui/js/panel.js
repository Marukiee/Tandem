// The small panel above the tray icon: who is connected, what is moving, what plays, and a way into the app.
import { html, render, useEffect, useRef } from "../vendor/preact-htm.js";
import { call, connect, native } from "./backend.js";
import { BatteryCard, batteryList } from "./batteries.js";
import { DeviceGlyph, PlayerCard, fmtSize } from "./components.js";
import { Icon } from "./icons.js";
import { setLanguage, t } from "./i18n.js";
import { say, set, state, useStore } from "./store.js";

const failed = (e) => say(String(e && e.message ? e.message : e));

function Row({ device }) {
  const battery = device.status && device.status.battery;
  const send = async () => { try { await call("pick_and_send", { ids: [device.id] }); } catch (e) { failed(e); } };
  const clipboard = async () => {
    try { await call("send_clipboard", { ids: [device.id] }); say(t("clipboard_sent")); }
    catch (e) { failed(e === "no-text" ? t("no_text") : e); }
  };
  return html`<div class=${"row" + (device.online ? "" : " dim")}>
    <${DeviceGlyph} device=${device} size=${34} />
    <div class="grow">
      <div class="name ellipsis">${device.name}</div>
      <div class=${"sub" + (device.online ? " ok" : device.ble ? " ble" : "")}>${device.online ? t("connected") : device.ble ? t("over_bluetooth") : t("not_connected")}${battery ? " · " + battery.level + "%" : ""}</div>
    </div>
    <button class="ib solid" title=${t("send_files")} disabled=${!device.online} onClick=${send}><${Icon} name="send" size=${16} /></button>
    <button class="ib solid" title=${t("send_clipboard")} disabled=${!device.online} onClick=${clipboard}><${Icon} name="clipboard" size=${16} /></button>
  </div>`;
}

/**
 * The watch of a phone, under that phone, as a device of its own: how it is doing, and the sound and the mouse with one press each. A
 * button that is on says so, and stops with the same press.
 */
function WatchRow({ phone }) {
  const watch = phone.status.watch;
  const reachable = phone.online && watch.online;
  const steering = reachable && watch.mouse && state.self && watch.mouse.target === state.self.id;
  const go = (command, args) => call(command, { id: phone.id, ...args }).catch(failed);
  const name = watch.name || t("watch");
  const sub = !reachable ? (phone.online && watch.bluetoothOff ? t("watch_bt_off", phone.name) : t("watch_away")) : [t("connected"), watch.level != null ? watch.level + "%" : "", steering ? t("watch_mouse_here") : watch.sleeping ? t("watch_recording") : ""].filter(Boolean).join(" · ");
  return html`<div class=${"row" + (reachable ? "" : " dim")}>
    <span class="glyph" style="width:34px;height:34px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:var(--accent-soft);color:var(--accent);flex:none"><${Icon} name="device-watch" size=${18} /></span>
    <div class="grow">
      <div class="name ellipsis">${name}</div>
      <div class=${"sub" + (reachable ? " ok" : "")}>${sub}</div>
    </div>
    <button class=${"ib solid" + (watch.ringing ? " on" : "")} title=${watch.ringing ? t("stop_sound") : t("play_sound")} aria-pressed=${!!watch.ringing} disabled=${!reachable}
      onClick=${() => go("ring_watch", { on: !watch.ringing })}><${Icon} name=${watch.ringing ? "bell-off" : "bell-ringing"} size=${16} /></button>
    <button class=${"ib solid" + (steering ? " on" : "")} title=${steering ? t("watch_mouse_stop") : t("watch_as_mouse")} aria-pressed=${!!steering} disabled=${!reachable}
      onClick=${() => (steering ? go("stop_watch_mouse") : go("start_watch_mouse", { table: false }))}><${Icon} name="pointer" size=${16} /></button>
  </div>`;
}

function Panel() {
  useStore();
  const box = useRef(null);
  const awake = !!(state.settings.keepAwake && state.canKeepAwake);
  const keepAwake = async () => {
    if (!state.canKeepAwake) return say(t("keep_awake_why"));
    try { set({ settings: await call("set_settings", { patch: { keepAwake: !awake } }) }); } catch (e) { failed(e); }
  };
  const online = state.devices.filter((d) => d.online);
  const active = state.transfers.filter((x) => x.state === "active");
  const playing = online
    .flatMap((d) => (state.players[d.id] || []).map((p) => ({ device: d, player: p })))
    .sort((a, b) => Number(b.player.playing) - Number(a.player.playing))[0];

  // Ask the shell for the height this content needs; its bottom edge stays on the taskbar.
  useEffect(() => {
    if (!native || !box.current) return undefined;
    // The content, plus the padding (2 x 14) and the border (2 x 1) of the box around it.
    const observer = new ResizeObserver(() => call("resize_panel", { height: box.current.offsetHeight + 30 }));
    observer.observe(box.current);
    return () => observer.disconnect();
  }, []);

  return html`<div class="panelbox"><div ref=${box} style="display:flex;flex-direction:column;gap:8px">
    <div style="display:flex;align-items:center;gap:10px;padding:0 4px 4px">
      <img src="icons/brand.png" width="28" height="28" style="border-radius:7px" alt="" />
      <div class="grow">
        <div style="font:600 15px var(--font-display)">Tandem</div>
        <div class="small muted">${state.devices.length ? t("panel_connected", online.length, state.devices.length) : t("panel_none")}</div>
      </div>
      <button class=${"ib solid" + (awake ? " on" : "") + (state.canKeepAwake ? "" : " grey")} title=${t("keep_awake")} aria-pressed=${awake} onClick=${keepAwake}><${Icon} name="coffee" size=${16} /></button>
    </div>

    <${BatteryCard} items=${batteryList(state.self, state.devices, state.players)}
      onOpen=${(item) => call("show_main", { device: item.id === "this" ? null : item.device || item.id }).catch(failed)} />

    ${state.offers.map((o) => html`<div class="banner" key=${o.offer} style="padding:8px 10px;gap:8px">
      <${Icon} name="download" size=${18} />
      <div class="grow"><div class="small" style="font-weight:600">${t("wants_to_send", o.fromName, o.items.length)}</div></div>
      <button class="btn accent small" onClick=${() => call("accept_offer", { from: o.from, offer: o.offer }).catch(failed)}>${t("accept")}</button>
      <button class="btn small" onClick=${() => call("decline_offer", { from: o.from, offer: o.offer }).catch(failed)}>${t("decline")}</button>
    </div>`)}

    ${active.map((x) => html`<div class="card" key=${x.id} style="padding:8px 12px">
      <div style="display:flex;gap:8px"><span class="grow ellipsis small" style="font-weight:600">${x.name}</span><span class="small muted">${fmtSize(x.speed || 0)}/s</span></div>
      <div class="prog"><i style=${`width:${x.total ? Math.round((x.done / x.total) * 100) : 0}%`}></i></div>
    </div>`)}

    ${state.devices.map((d) => html`<${Row} device=${d} key=${d.id} />${d.status && d.status.watch && d.status.watch.paired && html`<${WatchRow} phone=${d} key=${d.id + "-watch"} />`}`)}

    ${playing && html`<div class="card"><${PlayerCard} device=${playing.device} player=${playing.player} compact=${true} /></div>`}

    <button class="btn accent" style="justify-content:center;margin-top:2px" onClick=${() => call("show_main")}>${t("panel_open")}</button>
    ${state.say && html`<div class="small muted" style="text-align:center" role="status">${state.say}</div>`}
  </div></div>`;
}

await connect();
setLanguage(state.settings.language, state.systemLanguage);
render(html`<${Panel} />`, document.getElementById("root"));
// The panel stays loaded while it is hidden: look again when it comes back.
window.addEventListener("focus", async () => set(await call("get_state")));
