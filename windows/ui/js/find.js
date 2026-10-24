// Finding a computer: Play sound on its page, where it was last seen, and the sound this computer plays when it is looked for.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { ago } from "./components.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say, set, state } from "./store.js";

// What the computers that were rung say: they ring now, or stopped. One that never says (an older version) is let go after a while.
const LONGEST = 135000;
const timers = new Map();
const ringing = () => state.ringingDevices || [];

function mark(id, on) {
  clearTimeout(timers.get(id));
  timers.delete(id);
  set({ ringingDevices: on ? [...new Set([...ringing(), id])] : ringing().filter((d) => d !== id) });
  if (on) timers.set(id, setTimeout(() => mark(id, false), LONGEST));
}

listen("ringing", ({ id, on }) => mark(id, on));

/** The button on the page of a computer. It always looks the same; a press that cannot work says why. */
export function PlaySoundButton({ device }) {
  const on = ringing().includes(device.id);
  const press = async () => {
    if (on) {
      mark(device.id, false);
      return call("ring", { id: device.id, on: false }).catch(() => {});
    }
    if (!device.online) return say([t("sound_offline", device.name), lastSeenText(device)].filter(Boolean).join(" "));
    if (!(device.caps || []).includes("ring")) return say(t("sound_old", device.name));
    mark(device.id, true);
    try {
      await call("ring", { id: device.id, on: true });
      say(t("plays_sound", device.name));
    } catch (e) {
      mark(device.id, false);
      say(String(e && e.message ? e.message : e));
    }
  };
  return html`<button class=${"btn" + (on ? " on" : "")} onClick=${press}>
    <${Icon} name=${on ? "bell-off" : "bell-ringing"} size=${17} />${on ? t("stop_sound") : t("play_sound")}</button>`;
}

/** When and where a computer was last connected to this one, in a few words, or nothing when that is not known. */
export function lastSeenText(device) {
  const seen = device.lastSeen;
  if (!seen) return "";
  // Connected, the network is a chip at the top of the page; this line is only for where it was last.
  if (device.online) return "";
  const when = ago(seen.atMs);
  if (seen.ssid) return t("last_seen_on", when, seen.ssid);
  if (seen.route === "lan") return t("last_seen_lan", when);
  if (seen.route === "tailnet") return t("last_seen_tailnet", when);
  return t("last_seen", when);
}

export function LastSeen({ device }) {
  const text = lastSeenText(device);
  if (!text) return null;
  return html`<div class="small muted" style="margin:-4px 2px 0;display:flex;gap:6px;align-items:center">
    <${Icon} name=${device.online ? "wifi" : "hourglass"} size=${14} />${text}</div>`;
}

/** The sound this computer plays when another device looks for it: the sounds of the system, grouped, and a button to hear it. */
export function FindSoundRow({ SettingRow }) {
  const [gallery, setGallery] = useState({ sounds: [], chosen: null });
  useEffect(() => { call("find_sounds").then(setGallery).catch(() => {}); }, []);
  const groups = [...new Set(gallery.sounds.map((s) => s.group))];
  const choose = (path) => { setGallery({ ...gallery, chosen: path }); call("find_choose", { path }).catch(() => {}); };
  return html`<${SettingRow} icon="bell-ringing" title=${t("find_sound")} sub=${t("find_sound_sub")}>
    <div style="display:flex;gap:6px">
      <select value=${gallery.chosen || ""} onChange=${(e) => choose(e.target.value)}>
        ${groups.map((group) => html`<optgroup label=${group}>
          ${gallery.sounds.filter((s) => s.group === group).map((s) => html`<option value=${s.path}>${s.name}</option>`)}</optgroup>`)}
      </select>
      <button class="btn small" disabled=${!gallery.chosen} title=${t("find_preview")}
        onClick=${() => call("find_preview", { path: gallery.chosen }).catch(() => {})}><${Icon} name="player-play" size=${15} /></button>
    </div>
  <//>`;
}
