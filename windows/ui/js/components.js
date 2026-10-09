// Pieces both windows use.
import { html, useEffect, useRef, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { state } from "./store.js";

export const platformIcon = (platform) =>
  ({ android: "device-mobile", ios: "device-mobile", macos: "device-laptop", windows: "device-desktop", linux: "device-desktop" })[platform] || "devices";

export function fmtSize(bytes) {
  if (!bytes) return "0 KB";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let n = bytes;
  while (n >= 1000 && i < units.length - 1) { n /= 1000; i++; }
  return (n >= 100 || i === 0 ? Math.round(n) : n.toFixed(1)).toString().replace(".", ",") + " " + units[i];
}

export function fmtTime(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600), m = Math.floor(total / 60) % 60, s = total % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(s).padStart(2, "0");
}

export function ago(ts) {
  const diff = Math.max(0, Date.now() - ts);
  const m = Math.floor(diff / 60000);
  const rtf = new Intl.RelativeTimeFormat(document.documentElement.lang, { numeric: "auto" });
  if (m < 1) return rtf.format(0, "minute");
  if (m < 60) return rtf.format(-m, "minute");
  if (m < 1440) return rtf.format(-Math.floor(m / 60), "hour");
  return rtf.format(-Math.floor(m / 1440), "day");
}

export function DeviceGlyph({ device, size = 40 }) {
  return html`<span class=${"glyph" + (device.online ? " on" : "")} style=${`width:${size}px;height:${size}px`}>
    <${Icon} name=${platformIcon(device.platform)} size=${Math.round(size * 0.5)} />
  </span>`;
}

/** A number that runs to its new value instead of jumping, so a battery that charges up or drains reads as moving. */
function useCountUp(target, ms = 520) {
  const [shown, setShown] = useState(target);
  const from = useRef(target);
  useEffect(() => {
    const start = performance.now();
    const begin = from.current;
    let frame = 0;
    const tick = (now) => {
      const t = Math.min(1, (now - start) / ms);
      // Fast at first and then settling, the way the rest of the interface moves.
      const eased = 1 - Math.pow(1 - t, 3);
      const value = Math.round(begin + (target - begin) * eased);
      from.current = value;
      setShown(value);
      if (t < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [target]);
  return shown;
}

/** The battery as a ring. The arc only closes at 100, and a bolt (not a word) says it is charging. Everything that changes moves: the arc
 *  runs, the colour fades, the number counts, and the bolt grows in (or out) and takes the number with it. */
export function BatteryRing({ battery, size = 66 }) {
  const level = battery.level;
  const stroke = size * 0.11;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const arc = level >= 100 ? 1 : (level / 100) * 0.945;
  const colour = battery.charging ? "var(--ok)" : level <= 15 ? "var(--bad)" : "var(--accent)";
  const shown = useCountUp(level);
  return html`<div class="ring" style=${`width:${size}px;height:${size}px`} title=${t("battery") + " " + level + "%"}>
    <svg width=${size} height=${size}>
      <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke="var(--line-strong)" stroke-width=${stroke} />
      <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke=${colour} stroke-width=${stroke} stroke-linecap="round"
        stroke-dasharray=${`${c * arc} ${c}`} style="transition: stroke-dasharray 0.8s cubic-bezier(0.2, 0.9, 0.3, 1), stroke 0.45s ease" />
    </svg>
    <div class="mid">
      <span class="num" style=${`font-size:${size * 0.29}px`}>${shown}</span>
      <span class=${"bolt-wrap" + (battery.charging ? " on" : "")} style=${`--h:${size * 0.2}px`}>
        <${Icon} name="bolt" size=${size * 0.2} class="bolt" filled=${true} stroke=${1.5} />
      </span>
    </div>
  </div>`;
}

export function Chip({ icon, children, tone = "" }) {
  return html`<span class=${"chip " + tone}>${icon && html`<${Icon} name=${icon} size=${14} />`}${children}</span>`;
}

export function Switch({ on, onChange, label, disabled }) {
  return html`<button class=${"switch" + (on ? " on" : "")} role="switch" aria-checked=${on} aria-label=${label} disabled=${!!disabled} onClick=${() => !disabled && onChange(!on)}></button>`;
}

const routeText = { lan: "local_network", tailnet: "tailscale", other: "internet" };

/** What is known of how this device is connected, as small labels under its name. */
export function deviceChips(d) {
  const chips = [];
  chips.push(d.online
    ? html`<${Chip} icon="circle-check" tone="ok">${t("connected")}<//>`
    : html`<${Chip} icon="cloud-off">${t("not_connected")}<//>`);
  if (!d.online) return chips;
  if (d.route) chips.push(html`<${Chip} icon=${d.route === "lan" ? "wifi" : "link"}>${t(routeText[d.route])}<//>`);
  if (d.rttMs != null) chips.push(html`<${Chip}>${d.rttMs} ms<//>`);
  const net = d.status && d.status.network;
  const hotspot = d.status && d.status.hotspot === true;
  if (net && hotspot && net.kind === "cellular") {
    chips.push(html`<${Chip} icon="antenna-bars-5" tone="accent">${t("hotspot_cellular")}<//>`);
  } else {
    if (net) {
      const text = { wifi: net.ssid ? t("on_wifi_ssid", net.ssid) : t("on_wifi"), cellular: net.roaming ? t("on_cellular_roaming") : t("on_cellular"), ethernet: t("on_ethernet"), none: t("no_network"), other: t("no_network") }[net.kind];
      chips.push(html`<${Chip} icon=${net.kind === "cellular" ? "antenna-bars-5" : "wifi"}>${text}<//>`);
    }
    if (hotspot) chips.push(html`<${Chip} icon="antenna-bars-5" tone="accent">${t("hotspot_on")}<//>`);
  }
  if (d.status && d.status.dnd) chips.push(html`<${Chip} icon="moon">${t("dnd")}<//>`);
  return chips;
}

/** The player of a device: cover, what plays, the buttons, and a thin bar that can be dragged. */
export function PlayerCard({ device, player, compact = false }) {
  const cover = player.art !== "0" ? state.art[player.art] : null;
  const [, tick] = useState(0);
  const [scrub, setScrub] = useState(null);
  const held = useRef(null);
  const bar = useRef(null);
  const reportedAt = useRef(Date.now());

  useEffect(() => { reportedAt.current = Date.now(); held.current = null; }, [player.positionMs, player.playing, player.title]);
  useEffect(() => {
    if (!player.playing) return undefined;
    // Once a second: the time beside the bar has whole seconds only, and every redraw of the page costs power.
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [player.playing]);

  const duration = player.durationMs || 0;
  const natural = player.positionMs == null ? 0 : player.positionMs + (player.playing ? Date.now() - reportedAt.current : 0);
  let fraction = duration ? Math.min(1, natural / duration) : 0;
  if (held.current && Date.now() - held.current.at < 2500) fraction = held.current.fraction;
  if (scrub != null) fraction = scrub;

  const send = (action, positionMs) => call("media_command", { id: device.id, player: player.id, action, positionMs: positionMs ?? null });
  const along = (e) => {
    const box = bar.current.getBoundingClientRect();
    return Math.min(1, Math.max(0, (e.clientX - box.left) / box.width));
  };
  const down = (e) => {
    if (!player.canSeek || !duration) return;
    bar.current.setPointerCapture(e.pointerId);
    setScrub(along(e));
  };
  const move = (e) => { if (scrub != null) setScrub(along(e)); };
  const up = (e) => {
    if (scrub == null) return;
    const target = along(e);
    setScrub(null);
    held.current = { fraction: target, at: Date.now() };
    send("seek", Math.round(target * duration));
  };

  const sub = [player.artist, player.app].filter(Boolean).join(" · ");
  return html`<div class="player">
    <div class=${"cover" + (player.playing ? "" : " paused")} style=${cover ? `background-image:url(${cover})` : ""}>
      ${!cover && html`<${Icon} name="music" size=${compact ? 22 : 30} />`}
    </div>
    <div class="main">
      <div class="top">
        <div class="grow">
          <div class="t ellipsis">${player.title}</div>
          <div class="muted small ellipsis">${sub}</div>
        </div>
        <div class="btns">
          <button class="ib solid" title=${t("previous")} disabled=${!player.canPrev} onClick=${() => send("previous")}><${Icon} name="player-skip-back" size=${16} filled=${true} /></button>
          <button class="ib solid" title=${player.playing ? t("pause") : t("play")} onClick=${() => send("toggle")}><${Icon} name=${player.playing ? "player-pause" : "player-play"} size=${16} filled=${true} /></button>
          <button class="ib solid" title=${t("next")} disabled=${!player.canNext} onClick=${() => send("next")}><${Icon} name="player-skip-forward" size=${16} filled=${true} /></button>
        </div>
      </div>
      ${duration > 0 && html`<div class="progress">
        <div class=${"bar" + (scrub != null ? " drag" : "")} ref=${bar} onPointerDown=${down} onPointerMove=${move} onPointerUp=${up}>
          <div class="track"><div class="fill" style=${`width:${fraction * 100}%`}></div></div>
        </div>
        ${!compact && html`<div class="times"><span class=${scrub != null ? "live" : ""}>${fmtTime(fraction * duration)}</span><span>${fmtTime(duration)}</span></div>`}
      </div>`}
    </div>
  </div>`;
}
