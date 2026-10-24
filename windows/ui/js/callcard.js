// The floating window of a call on a phone: who, which phone, and Answer and Decline while it rings, Hang up while it goes on.
// The talking stays on the phone. The Rust side keeps the cards; this page draws them and lets the gone ones fade before they leave.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { setLanguage, setPlatform, t } from "./i18n.js";

const LEAVE_MS = 350;
let shown = [];
const leaving = new Map();

// The same six colours and the same sum as on the Mac, so a person has one colour on every computer.
const COLORS = ["#5b5bd6", "#934464", "#2f8f83", "#c0642b", "#3e7cc9", "#7a4fb5"];

function colour(name) {
  let sum = 0;
  for (const ch of name || "") sum = (Math.imul(sum, 31) + ch.codePointAt(0)) & 0xffffff;
  return COLORS[sum % COLORS.length];
}

function initials(name) {
  return (name || "").split(" ").filter((w) => /\p{L}/u.test(w[0] || "")).slice(0, 2).map((w) => w[0].toUpperCase()).join("");
}

function clock(since) {
  const seconds = Math.max(0, Math.floor((Date.now() - since) / 1000));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = String(seconds % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

function act(card, action) {
  call("call_action", { device: card.device, call: card.call, action }).catch(() => {});
}

function Button({ tone, icon, label, onClick }) {
  return html`<button class=${"call-button " + tone} title=${label} aria-label=${label} onClick=${onClick}>
    <${Icon} name=${icon} size=${19} stroke=${2} filled=${true} /></button>`;
}

function Card({ card, gone }) {
  const color = colour(card.name || "");
  const letters = initials(card.name);
  const state = {
    ringing: t("call_incoming"),
    dialing: t("call_dialing"),
    active: card.since ? clock(card.since) : "0:00",
    missed: t("call_missed"),
  }[card.phase];
  // Everything but the buttons drags the window: the attribute counts only for the element that carries it.
  return html`<div class=${"call-card" + (gone ? " leaving" : "")} data-tauri-drag-region>
    <div class="call-avatar" data-tauri-drag-region style=${`background:linear-gradient(color-mix(in srgb, ${color} 82%, transparent), ${color})`}>
      ${letters || html`<${Icon} name="user" size=${20} stroke=${2} filled=${true} />`}
    </div>
    <div class="call-words" data-tauri-drag-region>
      <div class="call-name" data-tauri-drag-region>${card.name || card.number || t("call_unknown")}</div>
      <div class=${"call-state " + card.phase} key=${card.phase} data-tauri-drag-region>${state}</div>
      <div class="call-via" data-tauri-drag-region>${t("call_via", card.deviceName)}</div>
    </div>
    <div class="call-buttons" key=${card.phase === "ringing" ? "two" : "one"}>
      ${/* The place for "Answer here" (while it rings) and "Take over here" / "Back to phone" (during the call), which will carry the
           sound of the call to this computer once there is a Bluetooth hands-free route for it. Left out until that works: a button that
           does nothing is never shown. */ null}
      ${card.phase === "ringing" && html`
        <${Button} tone="red" icon="phone" label=${t("call_decline")} onClick=${() => act(card, "reject")} />
        <${Button} tone="green" icon="phone" label=${t("call_answer")} onClick=${() => act(card, "answer")} />`}
      ${(card.phase === "active" || card.phase === "dialing") && html`
        <${Button} tone="red" icon="phone" label=${t("call_hangup")} onClick=${() => act(card, "hangup")} />`}
    </div>
  </div>`;
}

function draw() {
  const list = [...shown];
  // A card that went keeps its place while it fades.
  for (const [key, { card, index }] of leaving) {
    if (!list.some((c) => c.key === key)) list.splice(Math.min(index, list.length), 0, card);
  }
  render(html`${list.map((card) => html`<${Card} key=${card.key} card=${card} gone=${leaving.has(card.key)} />`)}`,
    document.getElementById("cards"));
}

function update(cards) {
  for (const [index, old] of shown.entries()) {
    if (!cards.some((c) => c.key === old.key) && !leaving.has(old.key)) {
      leaving.set(old.key, { card: old, index });
      setTimeout(() => { leaving.delete(old.key); draw(); }, LEAVE_MS);
    }
  }
  for (const card of cards) leaving.delete(card.key);
  shown = cards;
  draw();
}

const snapshot = await call("get_state").catch(() => ({}));
setPlatform(snapshot.platform);
setLanguage(snapshot.settings?.language, "");
update(await call("call_cards"));
listen("call-cards", update);
// The timer of a call that goes on.
setInterval(() => { if (shown.some((c) => c.phase === "active")) draw(); }, 1000);
