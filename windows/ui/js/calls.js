// Calling from this computer: the phone places the call and the person talks on the phone or its headset. What the phone still needs
// (the permission for calls) is said when the button is pressed, never on the page all the time.
import { html, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say } from "./store.js";

/** What a phone can dial: digits, a plus in front, and the star and hash of a keypad. */
const clean = (text) => text.replace(/[^0-9+*#]/g, "");

export function CallButton({ device }) {
  const [open, setOpen] = useState(false);
  const [number, setNumber] = useState("");
  const missing = (device.features || []).find((f) => f.id === "calls" && !f.ready);
  const press = () => (missing ? say(missing.why || missing.name) : setOpen(!open));
  const dial = () => {
    const dialed = clean(number);
    if (dialed.length < 3) return;
    setOpen(false);
    call("dial", { id: device.id, number: dialed })
      .then(() => say(t("call_started", device.name, dialed)))
      .catch(() => say(t("features_unreachable", device.name)));
  };
  return html`<span class="call-anchor">
    <button class=${"btn" + (open ? " on" : "")} disabled=${!device.online} onClick=${press}>
      <${Icon} name="phone" size=${17} />${t("call")}</button>
    ${open && html`<div class="card call-pop">
      <input autofocus placeholder=${t("call_number")} value=${number} onInput=${(e) => setNumber(e.target.value)}
        onKeyDown=${(e) => e.key === "Enter" && dial()} />
      <button class="btn accent small" disabled=${clean(number).length < 3} onClick=${dial}>${t("call")}</button>
    </div>`}
  </span>`;
}
