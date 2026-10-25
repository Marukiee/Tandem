// The batteries of this computer and the other devices as rings, as the widgets on the Mac and on Android show them, but in the style of
// this panel: its card, its icons, and its green and red (light and dark come with them). Windows and GNOME have no home screen widgets a
// program like Tandem can add (see docs/PARITY.md), so the rings live in the panel above the tray icon. On the charger the icon is green, and
// while a device plays something a soft shape turns behind its icon, as on Android.
import { html } from "../vendor/preact-htm.js";
import { platformIcon } from "./components.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";

const AWAY = 0.45;

/** Green, yellow under 20, red under 10. On the charger it is green whatever its level: it is getting better. */
export function tint(level, charging) {
  if (charging) return "var(--ok)";
  if (level < 10) return "var(--bad)";
  if (level < 20) return "var(--batt-low)";
  return "var(--ok)";
}

/** The rings in the order they are shown: this computer, then the connected devices, then the ones away (with their last level). */
export function batteryList(self, devices, players = {}) {
  const list = [];
  if (self && self.battery) list.push({ id: "this", name: self.name || "", icon: "device-laptop", level: self.battery.level, charging: !!self.battery.charging, away: false });
  const playing = (d) => d.online && (players[d.id] || []).some((p) => p.playing);
  const others = devices
    .filter((d) => d.status && d.status.battery)
    .map((d) => ({ id: d.id, name: d.name, icon: platformIcon(d.platform), level: d.status.battery.level, charging: !!d.status.battery.charging && d.online, away: !d.online, playing: playing(d) }))
    .sort((a, b) => Number(a.away) - Number(b.away) || a.name.localeCompare(b.name));
  return list.concat(others);
}

/** A circle whose edge goes in and out in soft waves, the cookie of the Android widget, as an SVG path in a box of `size`. */
function cookie(size, lobes = 9, depth = 0.11) {
  const c = size / 2;
  let d = "";
  for (let i = 0; i <= 120; i++) {
    const a = (2 * Math.PI * i) / 120;
    const r = c * (1 - (depth * (1 - Math.cos(lobes * a))) / 2);
    d += `${i ? "L" : "M"}${(c + r * Math.cos(a - Math.PI / 2)).toFixed(2)},${(c + r * Math.sin(a - Math.PI / 2)).toFixed(2)}`;
  }
  return d + "Z";
}

// What each ring last showed for the charger, so a change is played once (a green ripple on, the green drawing back off) and a ring that
// is drawn for the first time is not.
const lastCharging = new Map();

function Ring({ item, size, onOpen }) {
  const before = lastCharging.get(item.id);
  const at = before && before.charging !== item.charging && !item.away ? Date.now() : before ? before.at : 0;
  lastCharging.set(item.id, { charging: item.charging, at });
  // Kept for the length of the animation, so a panel drawn again meanwhile does not cut it off.
  const change = Date.now() - at < 1000 ? (item.charging ? " pulse-on" : " pulse-off") : "";
  const shape = Math.round(size * 0.66);
  const stroke = Math.max(3, size * 0.1);
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  // Anything below 100 leaves an opening, so 97 does not look like a closed ring with a flaw.
  const arc = item.level >= 100 ? 1 : (Math.max(0, item.level) / 100) * 0.945;
  const label = `${item.name} ${item.level}%${item.charging ? ", " + t("charging") : ""}`;
  return html`<button class="batt" style=${`opacity:${item.away ? AWAY : 1}`} title=${label} aria-label=${label} onClick=${() => onOpen && onOpen(item)}>
    <span class="batt-ring" style=${`width:${size}px;height:${size}px`}>
      <svg width=${size} height=${size}>
        <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke="var(--line-strong)" stroke-width=${stroke} />
        <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke=${tint(item.level, item.charging)} stroke-width=${stroke} stroke-linecap="round"
          stroke-dasharray=${`${c * arc} ${c}`} style="transition: stroke-dasharray 0.8s cubic-bezier(0.2, 0.9, 0.3, 1), stroke 0.45s ease" />
      </svg>
      <svg class=${"batt-shape" + (item.playing ? " on" : "")} width=${shape} height=${shape} aria-hidden="true"><path d=${cookie(shape)} /></svg>
      ${change && html`<span class=${"batt-pulse" + change} key=${item.id + change + at} style=${`width:${shape}px;height:${shape}px`}></span>`}
      <span class=${"batt-icon" + (item.charging ? " charging" : "")}><${Icon} name=${item.icon} size=${Math.round(size * 0.4)} stroke=${2} /></span>
    </span>
    <span class="batt-level">${item.charging && html`<span class="ok-text" style="display:flex"><${Icon} name="bolt" size=${12} filled=${true} stroke=${1.5} /></span>`}${item.level}%</span>
  </button>`;
}

/** A card of the panel with up to four rings a row. [onOpen] is given the ring that was clicked. */
export function BatteryCard({ items, onOpen }) {
  if (!items.length) return null;
  const rows = [];
  for (let i = 0; i < items.length; i += 4) rows.push(items.slice(i, i + 4));
  // Fewer rings get more room, the way the widgets do it.
  const size = items.length <= 2 ? 58 : 50;
  return html`<div class="card battcard">
    ${rows.map((row, index) => html`<div class="battrow" key=${index}>
      ${row.map((item) => html`<${Ring} item=${item} size=${size} onOpen=${onOpen} key=${item.id} />`)}
      ${Array.from({ length: rows.length > 1 ? 4 - row.length : 0 }, (_, n) => html`<span class="batt" key=${"gap" + n}></span>`)}
    </div>`)}
  </div>`;
}
