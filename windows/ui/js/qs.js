// The card in the corner of the screen while someone sends files, a link or a note with Quick Share: who, what, the PIN to
// compare, and the two buttons. After accepting it shows the progress, and after that what arrived.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { setLanguage, t } from "./i18n.js";

let items = [];

const bytes = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + " MB" : Math.max(1, Math.round(n / 1e3)) + " KB");

function summary(item) {
  const first = item.files[0] ? item.files[0].name : item.texts[0] ? item.texts[0].title : t("qs_something");
  const rest = item.files.length + item.texts.length - 1;
  return rest > 0 ? t("qs_and_more", first, rest) : first;
}

function Card({ item }) {
  const onlyText = item.files.length === 0 && item.texts.length > 0;
  const done = item.saved != null;
  const title = item.failure ? t("qs_stopped") : done ? t("qs_received_from", item.sender) : item.accepted ? t("qs_receiving_from", item.sender)
    : onlyText ? (item.texts[0].kind === "url" ? t("qs_wants_link", item.sender) : t("qs_wants_note", item.sender)) : t("qs_wants_share", item.sender);
  const sub = item.failure || (done ? (item.saved.length ? item.saved[0].split(/[\\/]/).pop() : t("qs_copied")) : onlyText ? summary(item) : summary(item) + " \u00b7 " + bytes(item.total));
  const answer = (accept) => call("qs_respond", { id: item.id, accept });
  const dismiss = () => call("qs_dismiss", { id: item.id });
  return html`<div class="qs-card">
    <div class="qs-head">
      <div class="qs-icon"><${Icon} name=${done ? "check" : "download"} size=${20} /></div>
      <div><div class="qs-title">${title}</div><div class="qs-sub">${sub}</div></div>
    </div>
    ${item.accepted && !done && !item.failure && html`<div class="qs-bar"><div style=${"width:" + (item.total ? Math.min(100, (item.done / item.total) * 100) : 0) + "%"}></div></div>`}
    ${!item.accepted && html`<div class="qs-pin">PIN <b>${item.pin}</b><span class="muted">${t("qs_pin_note")}</span></div>`}
    <div class="qs-buttons">
      ${done ? html`
        ${item.link ? html`<button class="btn" onClick=${() => { call("qs_open_link", { url: item.link }).catch(() => {}); dismiss(); }}>${t("qs_open_link")}</button>` : html`<span></span>`}
        <button class="btn accent" onClick=${dismiss}>${t("qs_done")}</button>`
      : item.failure ? html`<span></span><button class="btn" onClick=${dismiss}>${t("qs_close")}</button>`
      : item.accepted ? html`<span></span><button class="btn" onClick=${() => answer(false)}>${t("qs_cancel")}</button>`
      : html`<button class="btn" onClick=${() => answer(false)}>${t("qs_decline")}</button><button class="btn accent" onClick=${() => answer(true)}>${t("qs_accept")}</button>`}
    </div>
  </div>`;
}

function draw() {
  render(html`${items.map((item) => html`<${Card} key=${item.id} item=${item} />`)}`, document.getElementById("cards"));
}

const settings = await call("get_state").then((s) => s.settings).catch(() => ({ language: "auto" }));
setLanguage(settings.language, "");
items = (await call("qs_state")).incoming;
draw();
listen("quickshare", (snapshot) => { items = snapshot.incoming; draw(); });
