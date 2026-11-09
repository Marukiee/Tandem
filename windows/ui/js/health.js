// The health page: built like the one on the phone and the Mac, from the same seven days of the core.
import { html, useState, useEffect } from "../vendor/preact-htm.js";
import { Icon } from "./icons.js";
import { language, t } from "./i18n.js";

const STAGE_COLORS = {
  deep: "var(--accent)",
  // Mixed with the card, not with transparent: see-through pills showed the lines of the chart running through them.
  light: "color-mix(in srgb, var(--accent) 40%, var(--card))",
  rem: "#a06ee0",
  // The moments people look for, so they stand out. Not a warning colour: being awake for a bit is nothing to worry about.
  wake: "color-mix(in srgb, var(--text) 70%, var(--card))",
};
const LANES = ["wake", "rem", "light", "deep"];

const locale = () => (language() === "nl" ? "nl" : "en");

export function healthDuration(minutes) {
  return minutes >= 60 ? t("health_hm", Math.floor(minutes / 60), minutes % 60) : t("health_m", minutes);
}

const clockOf = (ms) => new Date(ms).toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit" });
/** Minutes from a midnight, negative for the evening before. */
const clockFromMidnight = (minutes) => {
  const inDay = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(inDay / 60)).padStart(2, "0")}:${String(inDay % 60).padStart(2, "0")}`;
};
/** Half a day on, so a change of the clock can not move a day. */
const dayName = (midnightMs) => new Date(midnightMs + 12 * 3600_000).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" });
const letter = (midnightMs) => new Date(midnightMs + 12 * 3600_000).toLocaleDateString(locale(), { weekday: "narrow" });
const number = (value) => Number(value).toLocaleString(locale());

/**
 * What the watch measured last, as three numbers on the page of the watch, each opening the health page. A computer sees whole days,
 * so the steps are of the last whole day. Nothing until this computer was given something.
 */
export function WatchGlance({ i, records, onOpen, goal }) {
  const night = (records || []).find((r) => r.kind === "sleep");
  const stepsDay = i ? [...i.week].reverse().find((d) => d.steps != null) : null;
  const resting = i && i.resting;
  if (!night && !stepsDay && !resting) return null;
  const score = night && i && i.latestNight && i.latestNight.id === night.id ? i.latestNight.score : null;
  const tile = (icon, title, value, caption, share) => html`<button class="card glance" title=${t("tab_health")} onClick=${onOpen}>
    <div class="glance-title">${share != null ? html`<span class="glance-ring" style=${`--p:${Math.round(share * 100)}`}><span><${Icon} name=${icon} size=${12} /></span></span>` : html`<${Icon} name=${icon} size=${15} />`}${title}</div>
    <${BigNumber} text=${value} />
    <div class="small muted ellipsis">${caption}</div>
  </button>`;
  return html`<div class="glances">
    ${night && tile("moon", t("watch_last_night"), healthDuration(night.sleepMinutes),
      score != null ? t("watch_score", score) : new Date(night.endMs).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" }))}
    ${stepsDay && tile("walk", t("health_steps_title"), number(stepsDay.steps), dayName(stepsDay.startMs), goal ? Math.min(1, stepsDay.steps / goal) : null)}
    ${resting && tile("heart", t("health_resting"), t("health_bpm", resting.latest),
      resting.usual != null ? t("health_usually", resting.usual) : t("health_nights_more", resting.nightsNeeded))}
  </div>`;
}

/** The digits large and the units small, so the number is what the eye finds first. */
function BigNumber({ text }) {
  const parts = String(text).split(/([0-9.,:]+)/).filter((part) => part !== "");
  return html`<div class="health-big">${parts.map((part, index) =>
    /[0-9]/.test(part) ? html`<span key=${index}>${part}</span>` : html`<span key=${index} class="health-unit">${part}</span>`)}</div>`;
}

/** A card with words behind it, for what a person could really wonder about. The title says that it opens. */
function Card({ title, more, children }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const handler = (e) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open]);
  return html`<div class="card health-card">
    ${title && html`<div class="health-title wide"><span class="grow">${title}</span>${more && html`<button class="health-info" title=${t("health_info")} aria-label=${t("health_info")} onClick=${() => setOpen(true)}><${Icon} name="info-circle" size=${18} /></button>`}</div>`}
    ${children}
    ${open && html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && setOpen(false)}><div class="dialog"><h2>${title}</h2><p class="health-more">${more}</p><div style="text-align:right;margin-top:14px"><button class="btn" onClick=${() => setOpen(false)}>${t("close")}</button></div></div></div>`}
  </div>`;
}

function DayLetters({ week }) {
  return html`<div class="health-days">${week.map((day, index) =>
    html`<span key=${day.startMs} class=${index === week.length - 1 ? "today" : ""}>${letter(day.startMs)}</span>`)}</div>`;
}

/** Seven days as rounded bars. A day without a measurement gets a low grey stripe: not zero, because nothing is known about it. */
function WeekBars({ values, week, picked, onPick }) {
  const top = Math.max(1, ...values.filter((v) => v != null)) * 1.08;
  const width = 196, height = 76, slot = width / values.length, bar = 18;
  return html`<div class="health-chart">
    <svg viewBox=${`0 0 ${width} ${height}`} width=${width} height=${height} role="img" onMouseLeave=${() => onPick(null)}>
      ${values.map((value, index) => {
        const x = slot * index + (slot - bar) / 2;
        if (value == null) return html`<rect key=${index} x=${x} y=${height - 5} width=${bar} height="5" rx="2.5" class="health-none" />`;
        const h = Math.max((value / top) * height, 8);
        return html`<rect key=${index} class="health-grow" x=${x} y=${height - h} width=${bar} height=${h} rx=${Math.min(bar, h) / 2}
          fill="var(--accent)" opacity=${picked == null || picked === index ? 1 : 0.3} />`;
      })}
      ${values.map((_, index) => html`<rect key=${"hit" + index} x=${slot * index} y="0" width=${slot} height=${height} fill="transparent"
        onMouseEnter=${() => onPick(index)} />`)}
    </svg>
    <${DayLetters} week=${week} />
  </div>`;
}

/** Seven days as a line, for what never comes near zero and so would say nothing as bars. */
function WeekLine({ values, week, usual, picked, onPick }) {
  const known = values.filter((v) => v != null).concat(usual != null ? [usual] : []);
  const low = Math.min(...known) - 3, high = Math.max(...known) + 3;
  const width = 196, height = 76, slot = width / values.length;
  const x = (index) => slot * index + slot / 2;
  const y = (value) => 8 + (1 - (value - low) / (high - low)) * (height - 16);
  const points = values.map((value, index) => (value == null ? null : `${x(index)},${y(value)}`)).filter(Boolean).join(" ");
  return html`<div class="health-chart">
    <svg viewBox=${`0 0 ${width} ${height}`} width=${width} height=${height} role="img" onMouseLeave=${() => onPick(null)}>
      ${usual != null && html`<line x1="0" x2=${width} y1=${y(usual)} y2=${y(usual)} class="health-guide-strong" />`}
      <polyline points=${points} fill="none" stroke="var(--accent)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      ${values.map((value, index) => value != null && html`<circle key=${index} cx=${x(index)} cy=${y(value)} r=${picked === index ? 5 : 3.5} fill="var(--accent)" />`)}
      ${values.map((_, index) => html`<rect key=${"hit" + index} x=${slot * index} y="0" width=${slot} height=${height} fill="transparent" onMouseEnter=${() => onPick(index)} />`)}
    </svg>
    <${DayLetters} week=${week} />
  </div>`;
}

/** From falling asleep to getting up, night by night: whether the nights start and end at the same time. Early at the top. */
function SleepWindows({ week }) {
  const beds = week.map((d) => d.bedMinutes).filter((v) => v != null);
  const wakes = week.map((d) => d.wakeMinutes).filter((v) => v != null);
  if (!beds.length || !wakes.length) return null;
  // Whole hours, so the lines fall on times that read easily.
  const from = Math.floor(Math.min(...beds) / 60) * 60;
  const until = Math.ceil(Math.max(...wakes) / 60) * 60;
  const marks = [...new Set([from, from + Math.floor((until - from) / 120) * 60, until])];
  const width = 420, height = 112, slot = width / week.length, bar = 18, span = Math.max(until - from, 60);
  const y = (minutes) => ((minutes - from) / span) * height;
  return html`<div class="health-windows">
    <div class="health-marks" style=${`height:${height}px`}>${marks.map((mark) => html`<span key=${mark}>${clockFromMidnight(mark)}</span>`)}</div>
    <div class="health-chart grow-x">
      <svg viewBox=${`0 0 ${width} ${height}`} preserveAspectRatio="none" width="100%" height=${height} role="img">
        ${marks.map((mark) => html`<line key=${mark} x1="0" x2=${width} y1=${y(mark)} y2=${y(mark)} class="health-guide" />`)}
        ${week.map((day, index) => day.bedMinutes != null && day.wakeMinutes != null && html`<rect key=${day.startMs} class="health-grow"
          x=${slot * index + (slot - bar) / 2} y=${y(day.bedMinutes)} width=${bar} height=${Math.max(y(day.wakeMinutes) - y(day.bedMinutes), bar)} rx=${bar / 2}
          fill="var(--accent)" />`)}
      </svg>
      <${DayLetters} week=${week} />
    </div>
  </div>`;
}

/**
 * A day of heart rate from midnight on. A gap of more than half an hour stays a gap: the watch was off the wrist.
 *
 * As the night can be: pointing at it shows the time and the heart rate there, a pinch on the touchpad (or Ctrl and the wheel) zooms in
 * on a part of the day, and dragging or scrolling sideways moves along it. The scale follows what is in view, so a small change shows.
 */
function DayHeartLine({ curve }) {
  const [pointer, setPointer] = useState(null);
  const [view, setView] = useState({ zoom: 1, from: 0 });
  const [drag, setDrag] = useState(null);
  const all = curve.filter((v) => v > 0);
  if (!all.length || curve.length < 2) return null;
  const last = curve.length - 1;
  const span = 1 / view.zoom;
  const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
  // A time on the axis, on a round five minutes and the whole day 0:00 to 0:00, so the labels read 6:00 and not 5:58.
  const minuteAt = (share) => Math.round((share * 1440) / 5) * 5;
  // The measured point nearest to a share of the day, within a quarter of an hour, or none where the watch was off.
  const pointAt = (share) => {
    const at = clamp(Math.round(share * last), 0, last);
    for (let d = 0; d <= 3; d++) for (const i of [at - d, at + d]) if (i >= 0 && i <= last && curve[i] > 0) return i;
    return null;
  };
  const first = clamp(Math.floor(view.from * last), 0, last);
  const end = clamp(Math.floor((view.from + span) * last) + 1, 0, last);
  const inView = curve.slice(first, end + 1).filter((v) => v > 0);
  const measured = inView.length ? inView : all;
  const low = Math.min(...measured) - 4, high = Math.max(...measured) + 4;
  const width = 560, height = 96;
  const x = (index) => (index / last - view.from) * view.zoom * width;
  const y = (value) => 6 + (1 - (value - low) / (high - low)) * (height - 12);
  let path = "", before = -100;
  curve.forEach((value, index) => {
    if (value <= 0) return;
    path += `${index - before > 6 ? "M" : "L"}${x(index).toFixed(1)},${y(value).toFixed(1)} `;
    before = index;
  });
  const shareOf = (e) => {
    const box = e.currentTarget.getBoundingClientRect();
    return clamp((e.clientX - box.left) / box.width, 0, 1);
  };
  const onWheel = (e) => {
    if (e.ctrlKey) {
      e.preventDefault();
      const centre = shareOf(e);
      setView((v) => {
        const zoom = clamp(v.zoom * Math.exp(-e.deltaY / 200), 1, 12);
        return { zoom, from: clamp(v.from + centre / v.zoom - centre / zoom, 0, 1 - 1 / zoom) };
      });
    } else if (view.zoom > 1.01 && Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
      e.preventDefault();
      setView((v) => ({ ...v, from: clamp(v.from + e.deltaX / width / v.zoom, 0, 1 - 1 / v.zoom) }));
    }
  };
  const onDown = (e) => {
    if (view.zoom <= 1.01) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag({ x: e.clientX, from: view.from, width: e.currentTarget.getBoundingClientRect().width });
  };
  const onMove = (e) => {
    setPointer(shareOf(e));
    if (drag) setView((v) => ({ ...v, from: clamp(drag.from - (e.clientX - drag.x) / drag.width / v.zoom, 0, 1 - 1 / v.zoom) }));
  };
  const at = pointer == null ? null : view.from + pointer * span;
  const point = at == null ? null : pointAt(at);
  const heading = at != null
    ? `${clockFromMidnight(point != null ? point * 5 : minuteAt(at))} · ${point != null ? t("health_bpm", curve[point]) : t("health_nothing")}`
    : view.zoom > 1.01 ? t("health_span", clockFromMidnight(minuteAt(view.from)), clockFromMidnight(minuteAt(view.from + span))) : t("health_heart_hint");
  return html`<div class="health-chart">
    <div class="health-heart-head small muted">
      <span class="grow">${heading}</span>
      ${view.zoom > 1.01 && html`<button class="health-link" onClick=${() => setView({ zoom: 1, from: 0 })}>${t("health_whole_day")}</button>`}
    </div>
    <div class="health-heart" onWheel=${onWheel} onPointerDown=${onDown} onPointerMove=${onMove} onPointerUp=${() => setDrag(null)}
      onPointerLeave=${() => { if (!drag) setPointer(null); }}>
      <svg viewBox=${`0 0 ${width} ${height}`} preserveAspectRatio="none" width="100%" height=${height} role="img" style="overflow:hidden;display:block">
        ${[0, 1, 2, 3, 4].map((q) => html`<line key=${q} x1=${(width * q) / 4} x2=${(width * q) / 4} y1="0" y2=${height} class="health-guide" />`)}
        <path d=${path} fill="none" stroke="var(--accent)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke" />
      </svg>
      ${pointer != null && html`<span class="health-heart-cursor" style=${`left:${pointer * 100}%`}></span>`}
      ${point != null && html`<span class="health-heart-dot" style=${`left:${(x(point) / width) * 100}%;top:${(y(curve[point]) / height) * 100}%`}></span>`}
    </div>
    <div class="health-axis">${[0, 1, 2, 3, 4].map((q) => html`<span key=${q}>${clockFromMidnight(minuteAt(view.from + (span * q) / 4))}</span>`)}</div>
  </div>`;
}

/** The night as it went: which stage when, on four lanes with the deepest at the bottom. Pointing says what the model thought then. */
function SleepTimeline({ r }) {
  const [pointer, setPointer] = useState(null);
  const total = r.stages.reduce((sum, run) => sum + run.seconds, 0);
  if (!total) return null;
  const width = 480, lane = 30, height = lane * LANES.length, bar = lane * 0.62;
  let at = 0, before = null;
  const links = [];
  const pills = [];
  r.stages.forEach((run, index) => {
    const x = (at / total) * width, length = Math.max((run.seconds / total) * width, 2);
    at += run.seconds;
    const laneIndex = LANES.indexOf(run.stage);
    if (laneIndex < 0) { before = null; return; }
    const y = lane * laneIndex + lane / 2;
    if (before != null && before !== laneIndex) {
      links.push(html`<line key=${"l" + index} x1=${x} x2=${x} y1=${lane * before + lane / 2} y2=${y} class="health-guide-strong" />`);
    }
    pills.push(html`<rect key=${index} x=${x} y=${y - bar / 2} width=${length} height=${bar} rx=${Math.min(bar, length) / 2} fill=${STAGE_COLORS[run.stage]} />`);
    before = laneIndex;
  });
  let heading = t("health_span", clockOf(r.startMs), clockOf(r.endMs));
  if (pointer != null) {
    let seconds = pointer * total;
    const run = r.stages.find((candidate) => (seconds -= candidate.seconds) <= 0);
    heading = `${clockOf(r.startMs + (r.endMs - r.startMs) * pointer)} · ${run ? t("health_stage_" + run.stage) : t("health_nothing")}`;
  }
  const move = (event) => {
    const box = event.currentTarget.getBoundingClientRect();
    setPointer(Math.min(Math.max((event.clientX - box.left) / box.width, 0), 1));
  };
  return html`<div class="health-timeline">
    <div class="small muted" style="font-variant-numeric:tabular-nums">${heading}</div>
    <div class="health-lanes">
      <div class="health-lane-names">${LANES.map((name) => html`<span key=${name} style=${`height:${lane}px`}>${t("health_stage_" + name)}</span>`)}</div>
      <svg viewBox=${`0 0 ${width} ${height}`} preserveAspectRatio="none" width="100%" height=${height} role="img" class="health-reveal"
        onMouseMove=${move} onMouseLeave=${() => setPointer(null)}>
        ${LANES.map((_, index) => html`<line key=${"g" + index} x1="0" x2=${width} y1=${lane * index + lane / 2} y2=${lane * index + lane / 2} class="health-guide" />`)}
        ${links}
        ${pills}
        ${pointer != null && html`<line x1=${pointer * width} x2=${pointer * width} y1="0" y2=${height} stroke="var(--text)" stroke-width="1.5" vector-effect="non-scaling-stroke" />`}
      </svg>
    </div>
  </div>`;
}

/**
 * One part of the quality of a night as Google's Sleep Score shows it: a bar with the range most adults fall in as a dashed box, the night
 * as a dot, and a word for whether it falls in that range.
 */
function QualityRow({ m }) {
  const names = { toSoundSleep: "health_q_to_sound", soundSleep: "health_q_sound", restlessness: "health_q_restless", interruptions: "health_q_interruptions", fullAwakenings: "health_q_awakenings" };
  const inRange = m.value >= m.low && m.value <= m.high;
  const pct = (v) => Math.max(0, Math.min(1, v / Math.max(1, m.max))) * 100;
  const end = Math.max(pct(m.value), 4);
  const shown = m.kind === "fullAwakenings" ? t("health_q_moments", m.value) : healthDuration(m.value);
  const badge = m.value > m.high ? t("health_q_above") : m.value < m.low ? t("health_q_below") : t("health_q_in");
  return html`<div class=${"quality-row" + (inRange ? " in" : " out")}>
    <div class="quality-head"><span class="grow">${t(names[m.kind])}<span class="muted"> · ${shown}</span></span><span class="quality-badge">${badge}</span></div>
    <div class="quality-bar">
      <span class="quality-track"></span>
      <span class="quality-fill" style=${`width:${end}%`}></span>
      <span class="quality-range" style=${`left:${pct(m.low)}%;width:${Math.max(pct(m.high) - pct(m.low), 4)}%`}></span>
      <span class="quality-dot" style=${`left:${end}%`}></span>
    </div>
  </div>`;
}

function Fact({ label, value }) {
  return html`<div class="health-fact"><span class="small muted">${label}</span><b>${value}</b></div>`;
}

function NightFacts({ r }) {
  return html`<div class="health-facts">
    <${Fact} label=${t("health_stat_asleep")} value=${r.efficiencyPercent + "%"} />
    <${Fact} label=${t("health_stage_wake")} value=${healthDuration(r.awakeMinutes)} />
    ${r.heartRateMean != null && html`<${Fact} label=${t("health_stat_heart")} value=${r.heartRateMean} />`}
    ${r.heartRateLow != null && html`<${Fact} label=${t("health_stat_lowest")} value=${r.heartRateLow} />`}
  </div>`;
}

function StageTiles({ r }) {
  if (r.lightMinutes == null || r.deepMinutes == null || r.remMinutes == null) return null;
  const tile = (stage, minutes) => html`<div class="health-fact" key=${stage}>
    <span class="small muted"><i class="health-dot" style=${`background:${STAGE_COLORS[stage]}`}></i>${t("health_stage_" + stage)}</span>
    <b>${healthDuration(minutes)}</b></div>`;
  return html`<div class="health-facts">${tile("light", r.lightMinutes)}${tile("deep", r.deepMinutes)}${tile("rem", r.remMinutes)}${tile("wake", r.awakeMinutes)}</div>`;
}

function ScorePart({ label, value }) {
  return html`<div class="health-part"><span class="small muted">${label}</span>
    <div class="health-meter"><div class="health-grow-x" style=${`width:${Math.max(Math.min(value, 100), 3)}%`}></div></div><b>${value}</b></div>`;
}

function ScoreRing({ score }) {
  const r = 30, stroke = 8, around = 2 * Math.PI * r;
  // Round caps reach past the ends of an arc, so the gap counts them and leaves a sliver on top, as on the phone.
  const gap = stroke + (around * 4) / 360;
  const usable = around - gap;
  const fill = (usable * Math.min(Math.max(score, 0), 100)) / 100;
  const rest = usable - fill - gap;
  return html`<svg width="72" height="72" viewBox="0 0 72 72" class="health-ring">
    ${rest > 1 && html`<circle cx="36" cy="36" r=${r} class="health-ring-track" stroke-dasharray=${`${rest} ${around}`} stroke-dashoffset=${-(fill + gap)} />`}
    ${fill > 1 && html`<circle cx="36" cy="36" r=${r} class="health-ring-fill" stroke-dasharray=${`${fill} ${around}`} />`}
    <text x="36" y="42" text-anchor="middle">${score}</text>
  </svg>`;
}

/** How many of the nights something needs are already there. A row of dots says "this is coming" better than an empty card. */
function NightsToGo({ needed, total }) {
  return html`<div class="health-dots">${Array.from({ length: total }, (_, index) => html`<i key=${index} class=${index < total - needed ? "on" : ""}></i>`)}</div>`;
}

/** The shape of most of this page: what it is, the number, and the week beside it. Pointing at a day shows it in the same place. */
function WeekCard({ title, more, number: shown, caption, week, dayNumbers, chart }) {
  const [picked, setPicked] = useState(null);
  return html`<${Card} title=${title} more=${more}>
    <div class="health-row">
      <div class="grow">
        <${BigNumber} text=${picked != null ? dayNumbers[picked] ?? "-" : shown} />
        <div class="small muted">${picked != null ? dayName(week[picked].startMs) : caption}</div>
      </div>
      ${chart(picked, setPicked)}
    </div>
  <//>`;
}

function EarlierNight({ r }) {
  const [open, setOpen] = useState(false);
  const canOpen = r.stages.length > 0;
  // A night that starts before midnight belongs to the morning it ends in.
  const day = new Date(r.endMs).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" });
  return html`<div class="card health-card">
    <button class="health-title wide" disabled=${!canOpen} aria-expanded=${open} onClick=${() => setOpen(!open)}>
      ${day}${canOpen && html`<span class=${"health-chevron" + (open ? " open" : "")}><${Icon} name="chevron-right" size=${14} /></span>`}
      <span class="grow"></span><span class="small muted">${healthDuration(r.sleepMinutes)}, ${r.efficiencyPercent}%</span>
    </button>
    ${open
      ? html`<${SleepTimeline} r=${r} /><${StageTiles} r=${r} /><${NightFacts} r=${r} />`
      : canOpen && html`<div class="health-stagebar">${r.stages.map((run, index) => html`<i key=${index} style=${`flex:${run.seconds};background:${STAGE_COLORS[run.stage] || "var(--line)"}`}></i>`)}</div>`}
  </div>`;
}

function Workout({ r }) {
  const day = new Date(r.startMs).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" });
  return html`<div class="card health-card">
    <div class="health-title"><${Icon} name="heart" size=${14} /> ${day}</div>
    <div class="health-facts">
      <${Fact} label=${t("health_length")} value=${healthDuration(Math.round((r.endMs - r.startMs) / 60000))} />
      ${r.heartRateMean != null && html`<${Fact} label=${t("health_stat_heart")} value=${r.heartRateMean} />`}
      ${r.heartRateHigh != null && html`<${Fact} label=${t("health_stat_highest")} value=${r.heartRateHigh} />`}
      ${r.steps > 0 && html`<${Fact} label=${t("health_steps_title")} value=${number(r.steps)} />`}
    </div>
  </div>`;
}

/** Everything the page shows once there is something to show. The sums are the core's, so this says the same as the phone. */
/** Sleep as a page of Health: last night large with the night and its quality, naps, the sleep of the week, and earlier nights. */
export function SleepDetail({ i, records }) {
  const week = i.week || [];
  const latest = records.find((r) => r.kind === "sleep");
  const insight = i.latestNight && latest && i.latestNight.id === latest.id ? i.latestNight : null;
  const earlier = records.filter((r) => r.kind === "sleep" && r !== latest).slice(0, 30);
  const now = i.thisWeek, before = i.lastWeek;
  return html`
    ${latest && html`<h3 class="health-section">${t("watch_last_night")}</h3>
      <${Card}>
        <div class="health-row">
          <div class="grow"><${BigNumber} text=${healthDuration(latest.sleepMinutes)} />
            <div class="small muted">${new Date(latest.endMs).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" })}</div></div>
          ${insight && html`<${ScoreRing} score=${insight.score} />`}
        </div>
        <${NightFacts} r=${latest} />
      <//>
      ${latest.stages.length > 0 && html`<${Card} title=${t("health_timeline")} more=${t("health_more_stages")}>
        <${SleepTimeline} r=${latest} />
        <${StageTiles} r=${latest} />
        ${insight && insight.wakeMoments.map((m) => html`<div key=${m.startMs} class="small muted">${t("health_wake_moment", clockOf(m.startMs), healthDuration(m.minutes))}</div>`)}
      <//>`}
      ${insight && insight.quality && insight.quality.length > 0 && html`<${Card} title=${t("health_quality")} more=${t("health_quality_more")}>
        <div class="quality">${insight.quality.map((m) => html`<${QualityRow} key=${m.kind} m=${m} />`)}</div>
        <div class="quality-legend small muted"><span class="quality-legend-box"></span>${t("health_q_typical")}</div>
      <//>`}
      ${insight && html`<${Card} title=${t("health_score")} more=${t("health_more_score")}>
        <${ScorePart} label=${t("health_part_length")} value=${insight.durationScore} />
        <${ScorePart} label=${t("health_part_calm")} value=${insight.continuityScore} />
        ${insight.stagesScore != null && html`<${ScorePart} label=${t("health_part_stages")} value=${insight.stagesScore} />`}
      <//>`}`}
    ${i.naps.length > 0 && html`<${Card} title=${t("health_naps")} more=${t("health_more_naps")}>
      <${BigNumber} text=${healthDuration(i.naps.reduce((sum, n) => sum + n.sleepMinutes, 0))} />
      ${i.naps.map((n) => html`<div key=${n.startMs}>${new Date(n.startMs).toLocaleString(locale(), { weekday: "short", hour: "2-digit", minute: "2-digit" })}, ${healthDuration(n.sleepMinutes)}</div>`)}
    <//>`}
    ${i.latestNight && html`<h3 class="health-section">${t("health_week")}</h3>
      ${now.meanSleepMinutes != null && html`<${WeekCard} title=${t("health_sleep")} number=${healthDuration(now.meanSleepMinutes)}
        caption=${before.meanSleepMinutes != null ? t("health_mean_night_before", healthDuration(before.meanSleepMinutes)) : t("health_mean_night")}
        week=${week} dayNumbers=${week.map((d) => (d.sleepMinutes != null ? healthDuration(d.sleepMinutes) : null))}
        chart=${(picked, pick) => html`<${WeekBars} values=${week.map((d) => d.sleepMinutes)} week=${week} picked=${picked} onPick=${pick} />`} />`}
      ${now.meanScore != null && html`<${WeekCard} title=${t("health_score")} number=${String(now.meanScore)}
        caption=${before.meanScore != null ? t("health_mean_score_before", before.meanScore) : t("health_mean_score")}
        week=${week} dayNumbers=${week.map((d) => (d.score != null ? String(d.score) : null))}
        chart=${(picked, pick) => html`<${WeekBars} values=${week.map((d) => d.score)} week=${week} picked=${picked} onPick=${pick} />`} />`}
      <${Card} title=${t("health_regular")} more=${t("health_more_regular")}>
        ${i.regularity
          ? html`<div>${t("health_regular_bed", clockFromMidnight(i.regularity.bedtimeMinutes), healthDuration(i.regularity.bedtimeSpreadMinutes))}</div>
            <div>${t("health_regular_wake", clockFromMidnight(i.regularity.wakeMinutes), healthDuration(i.regularity.wakeSpreadMinutes))}</div>`
          : html`<div class="small muted">${t("health_nights_more", i.regularityNightsNeeded)}</div><${NightsToGo} needed=${i.regularityNightsNeeded} total=${5} />`}
        <${SleepWindows} week=${week} />
      <//>
    `}
    ${earlier.length > 0 && html`<h3 class="health-section">${t("health_earlier")}</h3>
      ${earlier.map((r) => html`<${EarlierNight} key=${r.id} r=${r} />`)}`}
  `;
}

/** Activity as a page of Health: the steps and active minutes of the week, the workouts, and earlier workouts. */
export function ActivityDetail({ i, records }) {
  const week = i.week || [];
  // A computer sees whole days: only the phone hears the watch during the day.
  const lastSteps = week.map((d) => d.steps != null).lastIndexOf(true);
  const earlier = records.filter((r) => r.kind === "workout").slice(0, 30);
  const now = i.thisWeek;
  return html`
    ${(lastSteps >= 0 || now.workouts > 0) && html`<h3 class="health-section">${t("health_week")}</h3>`}
    ${lastSteps >= 0 && html`
      <${WeekCard} title=${t("health_steps_title")} number=${number(week[lastSteps].steps)} caption=${dayName(week[lastSteps].startMs)}
        week=${week} dayNumbers=${week.map((d) => (d.steps != null ? number(d.steps) : null))}
        chart=${(picked, pick) => html`<${WeekBars} values=${week.map((d) => d.steps)} week=${week} picked=${picked} onPick=${pick} />`} />
      <${WeekCard} title=${t("health_active_title")} more=${t("health_more_active")}
        number=${t("health_minutes", week[lastSteps].activeMinutes ?? 0)}
        caption=${t("health_active_week", week.reduce((sum, d) => sum + (d.activeMinutes || 0), 0))}
        week=${week} dayNumbers=${week.map((d) => (d.activeMinutes != null ? t("health_minutes", d.activeMinutes) : null))}
        chart=${(picked, pick) => html`<${WeekBars} values=${week.map((d) => d.activeMinutes)} week=${week} picked=${picked} onPick=${pick} />`} />`}
      ${now.workouts > 0 && html`<${Card} title=${t("health_workouts")}>
        <${BigNumber} text=${healthDuration(now.workoutMinutes)} />
        <div class="small muted">${t(now.workouts === 1 ? "health_workouts_week_one" : "health_workouts_week", now.workouts)}</div>
      <//>`}
    ${earlier.length > 0 && html`<h3 class="health-section">${t("health_earlier")}</h3>
      ${earlier.map((r) => html`<${Workout} key=${r.id} r=${r} />`)}`}
  `;
}

/** The heart as a page of Health: a warning when the resting rate is up, the heart rate of the day, and the resting rate of the week. */
export function HeartDetail({ i }) {
  const week = i.week || [];
  const heart = i.latestDayHeart;
  return html`
    ${i.alertAboveUsual != null && html`<div class="card tinted health-card" style="--tint:var(--accent-soft)"><div class="health-row"><${Icon} name="heart" size=${20} /><div>${t("health_alert", i.alertAboveUsual)}</div></div></div>`}
    ${heart && html`<${Card} title=${t("health_heart")} more=${t("health_more_heart")}>
      <div class="health-row"><${BigNumber} text=${t("health_bpm", heart.curve.filter((v) => v > 0).slice(-1)[0] ?? 0)} />
        <span class="small muted">${t("health_heart_range", dayName(heart.startMs), Math.min(...heart.curve.filter((v) => v > 0)), Math.max(...heart.curve))}</span></div>
      <${DayHeartLine} curve=${heart.curve} />
    <//>`}
    ${i.resting && html`<h3 class="health-section">${t("health_week")}</h3>`}
      ${i.resting && html`<${WeekCard} title=${t("health_resting")} more=${t("health_more_resting")} number=${t("health_bpm", i.resting.latest)}
        caption=${i.resting.usual != null ? t("health_usually", i.resting.usual) : t("health_nights_more", i.resting.nightsNeeded)}
        week=${week} dayNumbers=${week.map((d) => (d.resting != null ? t("health_bpm", d.resting) : null))}
        chart=${(picked, pick) => html`<div><${WeekLine} values=${week.map((d) => d.resting)} week=${week} usual=${i.resting.usual} picked=${picked} onPick=${pick} />
          ${i.resting.usual == null && html`<${NightsToGo} needed=${i.resting.nightsNeeded} total=${7} />`}</div>`} />`}
  `;
}

/** A circle with soft waves around its edge, after the shapes of Material You: a few deep lobes make a clover, many shallow ones a cookie. */
function wavyCircle(size, lobes, depth) {
  const c = size / 2, points = [];
  for (let step = 0; step <= 240; step++) {
    const a = (step / 240) * 2 * Math.PI - Math.PI / 2;
    const r = c * (1 - depth / 2 + (depth / 2) * Math.cos(lobes * a));
    points.push(`${(c + r * Math.cos(a)).toFixed(2)},${(c + r * Math.sin(a)).toFixed(2)}`);
  }
  return `M${points.join("L")}Z`;
}

/** The heart rate of a day as a small line, with gaps where the watch was off the wrist. */
function Sparkline({ curve }) {
  const measured = curve.filter((v) => v > 0);
  if (measured.length < 2) return null;
  const low = Math.min(...measured) - 2, high = Math.max(...measured) + 2;
  const width = 196, height = 48;
  let d = "", before = -100;
  curve.forEach((v, index) => {
    if (v <= 0) return;
    const x = (index / (curve.length - 1)) * width, y = (1 - (v - low) / (high - low)) * height;
    d += `${index - before > 6 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
    before = index;
  });
  return html`<svg viewBox=${`0 0 ${width} ${height}`} width=${width} height=${height} aria-hidden="true">
    <path d=${d} fill="none" stroke="var(--accent)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>`;
}

/** A card of a part of Health: its icon in a round, its name, the number large, a short word, and a little picture beside it. */
function PartCard({ icon, tone, title, value, caption, onClick, children }) {
  return html`<button class=${"card health-part-card tone-" + tone} onClick=${onClick}>
    <div class="health-part-head"><span class="health-part-icon"><${Icon} name=${icon} size=${18} /></span><span class="grow">${title}</span><${Icon} name="chevron-right" size=${16} /></div>
    <div class="health-row"><div class="grow"><div class="health-part-value">${value}</div><div class="small muted">${caption}</div></div>${children}</div>
  </button>`;
}

/**
 * Where Health opens: last night large with its score on a cookie, then a card per part with what matters most of it. Each opens the
 * whole of that part. Only parts with something in them: a card that says nothing makes the page look broken.
 */
export function HealthOverview({ i, records, goal, onOpen }) {
  const week = i.week || [];
  const night = records.find((r) => r.kind === "sleep");
  const insight = night && i.latestNight && i.latestNight.id === night.id ? i.latestNight : null;
  const lastSteps = week.map((d) => d.steps != null).lastIndexOf(true);
  const steps = lastSteps >= 0 ? week[lastSteps].steps : null;
  const heart = i.latestDayHeart;
  const resting = i.resting;
  const mean = i.thisWeek ? i.thisWeek.meanSleepMinutes : null;
  return html`
    ${night && html`<button class="health-hero" onClick=${() => onOpen("sleep")}>
      <svg class="health-clover" viewBox="0 0 160 160" aria-hidden="true"><path d=${wavyCircle(160, 4, 0.32)} /></svg>
      <div class="health-hero-main">
        <div class="health-hero-label"><span class="health-hero-icon"><${Icon} name="moon" size=${16} /></span>${t("watch_last_night")}</div>
        <${BigNumber} text=${healthDuration(night.sleepMinutes)} />
        <div class="small muted">${new Date(night.endMs).toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short" })}</div>
        <div class="health-pills">
          <span class="health-pill">${t("health_stat_asleep")}<b>${night.efficiencyPercent}%</b></span>
          ${night.awakeMinutes != null && html`<span class="health-pill">${t("health_stage_wake")}<b>${healthDuration(night.awakeMinutes)}</b></span>`}
          ${night.heartRateLow != null && html`<span class="health-pill">${t("health_stat_lowest")}<b>${t("health_bpm", night.heartRateLow)}</b></span>`}
        </div>
      </div>
      ${insight && html`<div class="health-hero-score">
        <div class="health-cookie"><svg class="health-cookie-shape" viewBox="0 0 120 120" aria-hidden="true"><path d=${wavyCircle(120, 9, 0.09)} /></svg><${ScoreRing} score=${insight.score} /></div>
        <span class="small">${t("health_score")}</span>
      </div>`}
    </button>`}
    <div class="health-parts">
      ${(mean != null || night) && html`<${PartCard} icon="moon" tone="sleep" title=${t("health_sleep")}
        value=${healthDuration(mean != null ? mean : night.sleepMinutes)} caption=${t(mean != null ? "health_mean_night" : "watch_last_night")}
        onClick=${() => onOpen("sleep")}>
        ${week.length > 0 && html`<${WeekBars} values=${week.map((d) => d.sleepMinutes)} week=${week} picked=${null} onPick=${() => {}} />`}
      <//>`}
      ${steps != null && html`<${PartCard} icon="walk" tone="activity" title=${t("health_activity_title")} value=${number(steps)}
        caption=${goal ? t("health_steps_of_goal", number(goal)) : dayName(week[lastSteps].startMs)} onClick=${() => onOpen("activity")}>
        ${goal && html`<div class="health-goal"><div style=${`width:${Math.min(100, (steps / goal) * 100)}%`}></div></div>`}
      <//>`}
      ${(resting || heart) && html`<${PartCard} icon="heart" tone="heart" title=${t("health_heart_title")}
        value=${resting ? t("health_bpm", resting.latest) : t("health_bpm", heart.curve.filter((v) => v > 0).slice(-1)[0] ?? 0)}
        caption=${resting ? (resting.usual != null ? t("health_usually", resting.usual) : t("health_nights_more", resting.nightsNeeded)) : t("health_heart")}
        onClick=${() => onOpen("heart")}>
        ${heart && html`<${Sparkline} curve=${heart.curve} />`}
      <//>`}
    </div>
  `;
}
