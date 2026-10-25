// Calling from this computer: the phone places the call and the person talks on the phone or its headset. Every call is asked first,
// here and only here, since a phone that dials by a slip of the pointer rings someone for real. What the phone still needs (the
// permission for calls) is said when someone wants to call, never on a page all the time.
import { html, useEffect, useRef, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say, set, state } from "./store.js";

/** What a phone can dial: digits, a plus in front, and the star and hash of a keypad. */
export const clean = (text) => text.replace(/[^0-9+*#]/g, "");

/** A typed text that is a number to call or write to as it is, and not the start of a name. */
export function typedNumber(text) {
  const trimmed = (text || "").trim();
  return /^[+0-9 ().*#-]+$/.test(trimmed) && trimmed.replace(/\D/g, "").length >= 3 ? trimmed : null;
}

/** Asks whether to call from `device`. `numbers` are `{ number, label }`; with more than one the question also asks which, starting
 * at the first. */
export function askCall(device, numbers, name = "") {
  const missing = (device.features || []).find((f) => f.id === "calls" && !f.ready);
  if (missing) { say(missing.why || missing.name); return; }
  if (!device.online) { say(t("features_unreachable", device.name)); return; }
  const dialable = numbers.filter((n) => clean(n.number).length >= 3).map((n) => ({ number: n.number, label: n.label || "" }));
  if (!dialable.length) return;
  set({ dialog: { kind: "call", id: device.id, numbers: dialable, name: (name || "").trim() } });
}

/** The question itself, over the page. Enter calls and Escape cancels, also while the field of a conversation had the keys. */
export function CallDialog({ dialog }) {
  const device = state.devices.find((d) => d.id === dialog.id);
  const deviceName = device ? device.name : "";
  const [pick, setPick] = useState(0);
  // Read by the key handler, which is made once for the question.
  const picked = useRef(0);
  picked.current = pick;
  const close = () => set({ dialog: null });
  const confirm = () => {
    // Enter and a click can both arrive for one press; only the question that is still up calls.
    if (state.dialog !== dialog) return;
    close();
    const chosen = dialog.numbers[picked.current] || dialog.numbers[0];
    call("dial", { id: dialog.id, number: chosen.number })
      .then(() => say(t("call_started", deviceName, dialog.name || chosen.number)))
      .catch(() => say(t("features_unreachable", deviceName)));
  };
  useEffect(() => {
    // Taken from the field that had it, or Enter there would send the text instead; it goes back once the question is answered.
    const before = document.activeElement;
    if (before && before.blur) before.blur();
    const onKey = (e) => {
      if (e.isComposing) return;
      if (e.key === "Enter") {
        // Someone who went to Cancel with Tab and pressed Enter wants Cancel, as everywhere in a browser.
        if (e.target && e.target.dataset && e.target.dataset.cancel != null) return;
        e.preventDefault();
        e.stopPropagation();
        confirm();
      } else if ((e.key === "ArrowDown" || e.key === "ArrowUp") && dialog.numbers.length > 1) {
        e.preventDefault();
        setPick((i) => Math.min(dialog.numbers.length - 1, Math.max(0, i + (e.key === "ArrowDown" ? 1 : -1))));
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      if (before && before.isConnected && before.focus) before.focus();
    };
  }, [dialog]);
  const chosen = dialog.numbers[pick] || dialog.numbers[0];
  const what = dialog.name ? [chosen.label, chosen.number].filter(Boolean).join(" ") : "";
  const several = dialog.numbers.length > 1;
  return html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && close()}>
    <div class="dialog call-ask" role="alertdialog" aria-labelledby="call-ask-title">
      <div class="call-ask-head">
        <span class="call-ask-mark"><${Icon} name="phone" size=${20} /></span>
        <div class="grow">
          <h2 id="call-ask-title">${t("call_ask", dialog.name || chosen.number)}</h2>
          <p class="muted">${several ? t("call_via_device", deviceName) : what ? t("call_ask_via", what, deviceName) : t("call_via_device", deviceName)}</p>
        </div>
      </div>
      ${several && html`<div class="call-ask-numbers" role="radiogroup">
        ${dialog.numbers.map((n, i) => html`<button role="radio" aria-checked=${i === pick} class=${"call-contact" + (i === pick ? " on" : "")} onClick=${() => setPick(i)}>
          <span class="t">${n.label || t("call_number")}</span><span class="s">${n.number}</span></button>`)}
      </div>`}
      <div class="call-ask-actions">
        <button class="btn" data-cancel onClick=${close}>${t("cancel")}</button>
        <button class="btn accent" onClick=${confirm}><${Icon} name="phone" size=${16} />${t("call")}</button>
      </div>
    </div>
  </div>`;
}
