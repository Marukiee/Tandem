// The small panel above the tray icon: who is connected, what is moving, what plays, and a way into the app.
import { html, render, useEffect, useRef } from "../vendor/preact-htm.js";
import { call, connect, native } from "./backend.js";
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
      <div class=${"sub" + (device.online ? " ok" : "")}>${device.online ? t("connected") : t("not_connected")}${battery ? " · " + battery.level + "%" : ""}</div>
    </div>
    <button class="ib solid" title=${t("send_files")} disabled=${!device.online} onClick=${send}><${Icon} name="send" size=${16} /></button>
    <button class="ib solid" title=${t("send_clipboard")} disabled=${!device.online} onClick=${clipboard}><${Icon} name="clipboard" size=${16} /></button>
  </div>`;
}

function Panel() {
  useStore();
  const box = useRef(null);
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
    </div>

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

    ${state.devices.map((d) => html`<${Row} device=${d} key=${d.id} />`)}

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
