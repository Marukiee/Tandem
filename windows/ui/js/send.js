// "Send with Tandem" from the file manager without a device named: a card that asks which one, like the Share sheet of a Mac. Every
// device is in it; one that is not connected is grey and says why when pressed. Devices that come online while it is open turn on.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { setLanguage, t } from "./i18n.js";
import { platformIcon } from "./components.js";

let devices = [];
let files = { count: 0, name: null };
let why = null;
let busy = false;

const choose = (to) => {
  if (busy) return;
  busy = true;
  call("send_pick", { to });
};

function Row({ icon, name, detail, on, onPress }) {
  return html`<button class=${"send-row" + (on ? "" : " off")} onClick=${onPress} title=${on ? t("send_pick_to", name) : ""}>
    <span class="send-dot"><${Icon} name=${icon} size=${18} /></span>
    <span class="send-text"><span class="send-name">${name}</span>${detail && html`<span class="send-detail">${detail}</span>`}</span>
    ${on && html`<span class="send-go"><${Icon} name="send" size=${16} /></span>`}
  </button>`;
}

function Card() {
  const online = devices.filter((d) => d.online);
  const title = files.count === 1 && files.name ? t("send_pick_one", files.name) : t("send_pick_many", files.count);
  return html`<div class="qs-card send-card">
    <div class="send-head"><div class="qs-title send-title">${title}</div><div class="qs-sub">${t("send_pick_choose")}</div></div>
    ${devices.length === 0
      ? html`<div class="send-empty">${t("send_pick_none")}</div>`
      : html`<div class="send-list">
          ${devices.map((d) => html`<${Row} key=${d.id} icon=${platformIcon(d.platform)} name=${d.name}
            detail=${d.online ? t("connected") : t("not_connected")} on=${d.online}
            onPress=${() => { if (d.online) choose([d.id]); else { why = t("send_pick_away", d.name); draw(); } }} />`)}
          ${online.length > 1 && html`<div class="send-line"></div>
            <${Row} icon="devices" name=${t("all_devices")} on=${true} onPress=${() => choose(online.map((d) => d.id))} />`}
        </div>`}
    <div class=${"send-why" + (why ? " shown" : "")}>${why && html`<${Icon} name="info-circle" size=${15} />`}<span>${why || ""}</span></div>
    <div class="qs-buttons send-buttons"><button class="btn quiet" onClick=${() => choose([])}>${t("cancel")}</button></div>
  </div>`;
}

function draw() {
  render(html`<${Card} />`, document.getElementById("cards"));
}

const state = await call("get_state").catch(() => ({ settings: { language: "auto" }, devices: [] }));
setLanguage(state.settings.language, "");
devices = state.devices || [];
files = await call("send_pick_state");
draw();
listen("devices", (list) => { devices = list; draw(); });
listen("send_pick", (next) => { files = next; why = null; busy = false; draw(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") choose([]); });
