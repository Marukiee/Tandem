// The screens next to this one, drawn the way the display settings of a desktop draw them: this screen in the middle, the others as
// boxes that are as big as their screens, to drag against the side where they sit. The arithmetic (where a box sticks, how it lines
// up) is in the app, the same for every system; this draws and drags.
import { html, useEffect, useRef, useState } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { platformIcon } from "./components.js";
import { t } from "./i18n.js";
import { say } from "./store.js";

const failed = (e) => say(String(e && e.message ? e.message : e));

const HEIGHT = 250;
const TRAY = 78;

/** How the screens map to the view: one scale for all of them, this screen in the middle of the room that is left for the boxes. */
function frameOf(width, main, devices) {
  const sizeOf = (d) => ({ w: d.width || 1440, h: d.height || 900 });
  const biggest = devices.reduce((a, d) => ({ w: Math.max(a.w, sizeOf(d).w), h: Math.max(a.h, sizeOf(d).h) }), { w: 1280, h: 800 });
  const roomW = main.width + 2 * biggest.w * 0.92;
  const roomH = main.height + 2 * biggest.h * 0.92;
  const scale = Math.min(width / roomW, (HEIGHT - 0) / roomH);
  return { scale, x: (width - main.width * scale) / 2, y: (HEIGHT - main.height * scale) / 2 };
}

const rectOf = (main, placed, size) => {
  const { edge, offset } = placed;
  switch (edge) {
    case "left": return { x: -size.w, y: offset, w: size.w, h: size.h };
    case "right": return { x: main.width, y: offset, w: size.w, h: size.h };
    case "top": return { x: offset, y: -size.h, w: size.w, h: size.h };
    default: return { x: offset, y: main.height, w: size.w, h: size.h };
  }
};

export function ArrangementEditor() {
  const [view, setView] = useState(null);
  const [width, setWidth] = useState(560);
  const [drag, setDrag] = useState(null); // { id, x, y } the middle of the box in the view
  const [ghost, setGhost] = useState(null);
  const root = useRef(null);
  const asking = useRef(false);

  const load = () => call("arrange_state").then(setView).catch(failed);
  const dragging = useRef(false);
  dragging.current = !!drag;
  useEffect(() => {
    load();
    let off;
    listen("devices", load).then((f) => { off = f; });
    // The desktop may be asking the person something (see the note under the screens): the state is read again now and then.
    const timer = setInterval(() => { if (!dragging.current) load(); }, 3000);
    return () => { clearInterval(timer); if (off) off(); };
  }, []);
  useEffect(() => {
    if (!root.current) return undefined;
    const watch = new ResizeObserver(() => root.current && setWidth(root.current.clientWidth));
    watch.observe(root.current);
    setWidth(root.current.clientWidth);
    return () => watch.disconnect();
  }, [view === null]);

  if (!view) return html`<div ref=${root} class="arrange"></div>`;
  const main = view.main;
  const devices = view.devices;
  const frame = frameOf(width, main, devices);
  const sizeOf = (d) => ({ w: d.width || 1440, h: d.height || 900 });
  const unplaced = devices.filter((d) => !d.placed);
  const toView = (r) => ({ left: frame.x + r.x * frame.scale, top: frame.y + r.y * frame.scale, width: r.w * frame.scale, height: r.h * frame.scale });

  // Where the box that is dragged is, as a box of the screens (this one at the origin).
  const modelBox = (d, at) => {
    const size = sizeOf(d);
    const w = size.w * frame.scale, h = size.h * frame.scale;
    return { x: (at.x - w / 2 - frame.x) / frame.scale, y: (at.y - h / 2 - frame.y) / frame.scale, width: size.w, height: size.h };
  };
  const snapArgs = (d, at) => ({ id: d.id, ...modelBox(d, at), x: Math.round(modelBox(d, at).x), y: Math.round(modelBox(d, at).y), snap: Math.round(90 / frame.scale) });

  const move = (d, event) => {
    const box = root.current.getBoundingClientRect();
    const at = { x: event.clientX - box.left, y: event.clientY - box.top };
    setDrag({ id: d.id, ...at });
    // The outline of where it would stick, asked now and then and never twice at once.
    if (!asking.current) {
      asking.current = true;
      call("arrange_snap", snapArgs(d, at)).then(setGhost).catch(() => {}).finally(() => { asking.current = false; });
    }
  };
  const drop = async (d, event) => {
    const box = root.current.getBoundingClientRect();
    const at = { x: event.clientX - box.left, y: event.clientY - box.top };
    setDrag(null);
    setGhost(null);
    try { setView(await call("arrange_place", snapArgs(d, at))); } catch (e) { failed(e); }
  };

  const boxFor = (d, index) => {
    const size = sizeOf(d);
    let style;
    if (drag && drag.id === d.id) {
      const w = size.w * frame.scale, h = size.h * frame.scale;
      style = { left: drag.x - w / 2, top: drag.y - h / 2, width: w, height: h };
    } else if (d.placed) {
      style = toView(rectOf(main, d.placed, size));
    } else {
      const tw = 112, th = 58, gap = 12;
      const total = unplaced.length * tw + (unplaced.length - 1) * gap;
      style = { left: (width - total) / 2 + index * (tw + gap), top: HEIGHT + (TRAY - th) / 2, width: tw, height: th };
    }
    const lifted = drag && drag.id === d.id;
    return html`<div key=${d.id} class=${"screen-box" + (d.online ? "" : " offline") + (d.placed || lifted ? "" : " tray") + (lifted ? " lifted" : "")}
      style=${{ ...style, transition: lifted ? "none" : undefined }}
      onPointerDown=${(e) => { e.currentTarget.setPointerCapture(e.pointerId); move(d, e); }}
      onPointerMove=${(e) => { if (drag && drag.id === d.id) move(d, e); }}
      onPointerUp=${(e) => { if (drag && drag.id === d.id) drop(d, e); }}
      onPointerCancel=${() => { setDrag(null); setGhost(null); }}>
      <${Icon} name=${platformIcon(d.platform)} size=${15} />
      <div class="n">${d.name}</div>
      <div class="s">${d.width ? `${d.width} × ${d.height}` : t("size_unknown")}</div>
    </div>`;
  };
  const mainStyle = toView({ x: 0, y: 0, w: main.width, h: main.height });
  const ghostStyle = ghost && toView({ x: ghost.x, y: ghost.y, w: ghost.width, h: ghost.height });

  const capture = view.capture || { state: "ready" };
  return html`<div>
  <div ref=${root} class="arrange" style=${{ height: HEIGHT + TRAY + "px" }}>
    ${ghostStyle && html`<div class="screen-box ghost" style=${ghostStyle}></div>`}
    <div class="screen-box main" style=${mainStyle}>
      <${Icon} name="device-desktop" size=${15} />
      <div class="n">${t("this_pc")}</div>
      <div class="s">${main.width} × ${main.height}</div>
    </div>
    ${devices.map((d) => boxFor(d, unplaced.indexOf(d)))}
    ${unplaced.length > 0 && html`<div class="arrange-hint">${t("arrange_hint")}</div>`}
  </div>
  ${capture.state === "starting" && html`<div class="small muted" style="margin-top:8px">${t("capture_starting")}</div>`}
  ${capture.state === "locked" && html`<div class="small muted" style="margin-top:8px">${t("capture_locked")}</div>`}
  ${capture.state === "failed" && html`<div class="small muted" style="margin-top:8px">${t("capture_failed", capture.reason || "?")}</div>`}
  </div>`;
}
