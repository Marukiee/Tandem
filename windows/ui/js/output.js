// This computer's sound on another device: one button on the page of each device that can play it, which turns into Stop while the
// sound goes there. Why it does not work (nothing to record, or the device does not play it) is only said when it is pressed.
import { html } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say, set, state } from "./store.js";

listen("output", (output) => set({ output: output || null }));
call("output_state").then((output) => set({ output: output || null })).catch(() => {});

/** Whether the device can be this computer's speaker. A phone from before the capability was there plays it too. */
export function canPlay(device) {
  return device.platform === "android" || (device.caps || []).includes("audio.play");
}

export function OutputButton({ device }) {
  const on = state.output === device.id;
  const press = () => (on ? call("output_stop") : call("output_start", { id: device.id }).then(() => say(t("output_started", device.name))))
    .catch((e) => say(String(e && e.message ? e.message : e)));
  return html`<button class=${"btn" + (on ? " on" : "")} disabled=${!device.online && !on} title=${t("output_tip", device.name)} onClick=${press}>
    <${Icon} name=${on ? "circle-x" : "volume"} size=${17} />${on ? t("output_stop") : t("output_use")}</button>`;
}
