// Calling from this computer: the phone places the call and the person talks on the phone or its headset. What the phone still needs
// (the permission for calls) is said when the button is pressed, never on the page all the time.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say } from "./store.js";

/** What a phone can dial: digits, a plus in front, and the star and hash of a keypad. */
const clean = (text) => text.replace(/[^0-9+*#]/g, "");

export function CallButton({ device }) {
  const [open, setOpen] = useState(false);
  const [number, setNumber] = useState("");
  const [found, setFound] = useState([]);
  // A name typed instead of a number: the contacts of the phone that match, as they are typed.
  useEffect(() => {
    const query = number.trim();
    if (!open || query.length < 2 || clean(query) === query.replace(/\s/g, "")) {
      setFound([]);
      return undefined;
    }
    const timer = setTimeout(() => call("sms_contacts", { id: device.id, query }).then((list) => setFound((list || []).slice(0, 6))).catch(() => setFound([])), 250);
    return () => clearTimeout(timer);
  }, [number, open]);
  const missing = (device.features || []).find((f) => f.id === "calls" && !f.ready);
  const press = () => (missing ? say(missing.why || missing.name) : setOpen(!open));
  const dial = (chosen) => {
    const dialed = clean(typeof chosen === "string" ? chosen : number);
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
      <input autofocus placeholder=${t("call_number_or_name")} value=${number} onInput=${(e) => setNumber(e.target.value)}
        onKeyDown=${(e) => e.key === "Enter" && dial()} />
      <button class="btn accent small" disabled=${clean(number).length < 3} onClick=${() => dial()}>${t("call")}</button>
      ${found.length > 0 && html`<div class="call-found">
        ${found.map((c) => html`<button class="call-contact" onClick=${() => dial(c.number)}>
          <span class="t">${c.name}</span><span class="s">${c.label ? c.label + " " : ""}${c.number}</span></button>`)}
      </div>`}
    </div>`}
  </span>`;
}
