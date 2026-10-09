// The question whether a device may look at this screen, and perhaps use its mouse and keyboard. A card in the corner with the
// three answers; on Linux the same question is also a notification, and the first answer counts.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { setLanguage } from "./i18n.js";

let items = [];

function Card({ item }) {
  const answer = (value) => call("ask_answer", { id: item.id, answer: value });
  return html`<div class="qs-card">
    <div class="qs-head">
      <div class="qs-icon"><${Icon} name=${item.control ? "pointer" : "device-desktop"} size=${20} /></div>
      <div><div class="qs-title">${item.title}</div><div class="qs-sub">${item.body}</div></div>
    </div>
    <div class="qs-buttons">
      <button class="btn quiet" onClick=${() => answer("deny")}>${item.deny}</button>
      <button class="btn" onClick=${() => answer("always")}>${item.always}</button>
      <button class="btn accent" onClick=${() => answer("allow")}>${item.allow}</button>
    </div>
  </div>`;
}

function draw() {
  render(html`${items.map((item) => html`<${Card} key=${item.id} item=${item} />`)}`, document.getElementById("cards"));
}

const settings = await call("get_state").then((s) => s.settings).catch(() => ({ language: "auto" }));
setLanguage(settings.language, "");
items = await call("ask_state");
draw();
listen("host_asks", (list) => { items = list; draw(); });
