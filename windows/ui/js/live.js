// The window of one phone's screen or camera. The frames arrive from the Rust side as H.264 access units in Annex B and
// are decoded by the browser engine (WebCodecs), which uses the graphics card where it can, and drawn on a canvas.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { ResizeEdges, WindowButtons } from "./chrome.js";
import { iconMarkup } from "./icons.js";
import { setPlatform, t } from "./i18n.js";
import { set } from "./store.js";

const tauri = window.__TAURI__;
// As text: the number is 64 bits long and a number of a web page keeps 53 of them.
const session = new URLSearchParams(location.search).get("session");
const canvas = document.getElementById("picture");
const context = canvas.getContext("2d");
const stateBox = document.getElementById("state");
const message = document.getElementById("message");
const closeButton = document.getElementById("close");
const bar = document.getElementById("bar");
const top = document.getElementById("top");

let name = "";
let kind = "screen";
let decoder = null;
let spsKey = "";
let waitingForKey = true;
let phoneRotation = 0;
let extraRotation = 0;
let pinned = false;
let hasPicture = false;
let lastAsk = 0;
// Clicking and typing on the phone: the phone says whether it lets this computer, and the person turns it on and off.
let controlGranted = false;
let controlOn = false;
// What is shown is a computer, not a phone: other words for what is needed before it can be used.
let computerPeer = false;
// Pictures that were decoded by the app itself (see video.rs) arrive as JPEG, in the order they were made.
let jpegAsked = 0;
let jpegShown = 0;

const say = (text, withClose = false) => {
  stateBox.classList.remove("hidden");
  message.textContent = text;
  closeButton.hidden = !withClose;
};
const clear = () => stateBox.classList.add("hidden");

// ---- Reading Annex B -----------------------------------------------------------------

/** The NAL units of an access unit: where each payload starts and ends, start codes left out. */
function units(bytes) {
  const found = [];
  let i = 0;
  while (i + 3 <= bytes.length) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && (bytes[i + 2] === 1 || (bytes[i + 2] === 0 && bytes[i + 3] === 1))) {
      const start = i + (bytes[i + 2] === 1 ? 3 : 4);
      if (found.length) found[found.length - 1][1] = i;
      found.push([start, bytes.length]);
      i = start;
    } else {
      i++;
    }
  }
  return found;
}

/** The codec string the decoder wants, from the profile and level in the parameter set. */
function codecOf(sps) {
  return "avc1." + [1, 2, 3].map((i) => sps[i].toString(16).padStart(2, "0")).join("");
}

// ---- Decoding ---------------------------------------------------------------------

function draw(frame) {
  const turn = (((phoneRotation + extraRotation) % 360) + 360) % 360;
  const swap = turn === 90 || turn === 270;
  const frameWidth = frame.displayWidth ?? frame.width;
  const frameHeight = frame.displayHeight ?? frame.height;
  const width = swap ? frameHeight : frameWidth;
  const height = swap ? frameWidth : frameHeight;
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  context.save();
  context.translate(width / 2, height / 2);
  context.rotate((turn * Math.PI) / 180);
  context.drawImage(frame, -frameWidth / 2, -frameHeight / 2);
  context.restore();
  frame.close();
  if (!hasPicture) {
    hasPicture = true;
    clear();
  }
}

async function setUp(sps) {
  const config = { codec: codecOf(sps), optimizeForLatency: true, hardwareAcceleration: "prefer-hardware", avc: { format: "annexb" } };
  let support = await VideoDecoder.isConfigSupported(config).catch(() => ({ supported: false }));
  if (!support.supported) {
    // Without the graphics card the software decoder may still do it.
    const soft = { ...config, hardwareAcceleration: "no-preference" };
    support = await VideoDecoder.isConfigSupported(soft).catch(() => ({ supported: false }));
  }
  if (!support.supported) {
    say(t("live_cannot_decode"), true);
    return false;
  }
  if (decoder && decoder.state !== "closed") decoder.close();
  decoder = new VideoDecoder({ output: draw, error: () => { waitingForKey = true; askKeyframe(); } });
  decoder.configure(support.config);
  return true;
}

function askKeyframe() {
  const now = performance.now();
  if (now - lastAsk < 700) return;
  lastAsk = now;
  call("live_keyframe", { session }).catch(() => {});
}

/** A picture the app decoded: it is drawn unless a later one was drawn already while this one was being unpacked. */
async function showJpeg(data) {
  const mine = ++jpegAsked;
  try {
    const bitmap = await createImageBitmap(new Blob([data], { type: "image/jpeg" }));
    if (mine < jpegShown) { bitmap.close(); return; }
    jpegShown = mine;
    draw(bitmap);
  } catch {
    // A picture that does not unpack is skipped; the next one follows at once.
  }
}

async function onFrame(buffer) {
  const bytes = new Uint8Array(buffer);
  const flags = bytes[0];
  if (flags & 4) return showJpeg(bytes.subarray(9));
  const keyframe = (flags & 1) !== 0;
  const timestamp = Number(new DataView(buffer).getBigUint64(1));
  const data = bytes.subarray(9);
  if (flags & 2 && decoder && decoder.state !== "closed") {
    decoder.reset();
    spsKey = "";
    waitingForKey = true;
  }
  if (keyframe) {
    const sps = units(data).map(([a, b]) => data.subarray(a, b)).find((unit) => (unit[0] & 0x1f) === 7);
    if (sps) {
      const key = Array.from(sps.subarray(0, 4)).join(",");
      if (!decoder || decoder.state === "closed" || key !== spsKey) {
        if (!(await setUp(sps))) return;
        spsKey = key;
      }
    }
    waitingForKey = false;
  }
  if (waitingForKey || !decoder || decoder.state !== "configured") {
    askKeyframe();
    return;
  }
  // The decoder is behind: a late picture is no picture, so wait for the next full one.
  if (!keyframe && decoder.decodeQueueSize > 4) {
    waitingForKey = true;
    askKeyframe();
    return;
  }
  try {
    decoder.decode(new EncodedVideoChunk({ type: keyframe ? "key" : "delta", timestamp, data }));
  } catch {
    waitingForKey = true;
    askKeyframe();
  }
}

// ---- The window -------------------------------------------------------------------

/** The window goes when the sharing is over, however it ended: the Rust side closes it and says in the main window what went wrong. */
const leave = () => { call("live_stop", { session }).catch(() => {}).finally(() => window.close()); };

async function start() {
  const text = (key) => t(key);
  const round = (id, icon, label) => {
    const button = document.getElementById(id);
    button.innerHTML = iconMarkup(icon, 18, 1.9);
    button.title = label;
    button.setAttribute("aria-label", label);
  };
  round("pin", "pin", text("live_pin"));
  round("turn", "refresh", text("live_turn"));
  round("copy", "copy", text("live_copy"));
  round("stop", "x", text("live_stop"));
  round("control", "pointer", text("live_control_start"));
  document.getElementById("helpclose").textContent = text("close");
  closeButton.textContent = text("close");

  // In a plain browser, which is how this window is tried while it is being made, a recording stands in for the phone.
  if (!tauri) return preview(new URLSearchParams(location.search).get("clip"));
  const channel = new tauri.core.Channel();
  channel.onmessage = (buffer) => { onFrame(buffer instanceof ArrayBuffer ? buffer : new Uint8Array(buffer).buffer); };
  // A session that is over by the time the window is up has nothing to show: the window just goes.
  const info = await call("live_attach", { session, onFrame: channel }).catch(() => null);
  if (!info) return leave();
  setPlatform(info.platform);
  set({ platform: info.platform });
  computerPeer = Boolean(info.computer);
  if (!info.native && !window.VideoDecoder) {
    say(t("live_cannot_decode"), true);
    return;
  }
  if (info.platform === "linux") {
    // No frame of the system around this window: the buttons for it float in the strip, and the edges resize.
    render(html`<${WindowButtons} />`, document.getElementById("chrome"));
    render(html`<${ResizeEdges} />`, document.getElementById("edges"));
  }
  name = info.name;
  kind = info.kind;
  phoneRotation = info.rotation || 0;
  document.title = name;
  if (info.accepted) controlGranted = Boolean(info.accepted.control);
  showControl();
  say(t(kind === "camera" ? "live_waiting_camera" : "live_waiting_screen", name));
  bar.hidden = false;
}

listen("live-accepted", (accepted) => {
  controlGranted = Boolean(accepted && accepted.control);
  showControl();
  if (!hasPicture) say(t("live_first_picture"));
});
listen("live-update", (update) => {
  if (typeof update.rotation === "number") phoneRotation = update.rotation;
  if (typeof update.control === "boolean") { controlGranted = update.control; showControl(); }
});

document.getElementById("pin").addEventListener("click", (e) => {
  pinned = !pinned;
  e.currentTarget.classList.toggle("on", pinned);
  call("live_pin", { session, on: pinned });
});
document.getElementById("turn").addEventListener("click", () => { extraRotation = (extraRotation + 90) % 360; });
document.getElementById("copy").addEventListener("click", () => {
  canvas.toBlob((blob) => { if (blob) navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]).catch(() => {}); });
});
document.getElementById("stop").addEventListener("click", leave);
closeButton.addEventListener("click", leave);
window.addEventListener("pagehide", () => { call("live_stop", { session }); });

// The bar steps aside while the pointer is still, so it never sits on the picture.
let hideTimer = 0;
document.addEventListener("mousemove", () => {
  top.classList.remove("away");
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => top.classList.add("away"), 2500);
});

/** The access units of a recording in Annex B: the parameter sets belong to the picture that follows them. */
function accessUnits(bytes) {
  const out = [];
  let pending = [];
  for (const [a, b] of units(bytes)) {
    const type = bytes[a] & 0x1f;
    // Back up over the start code so every unit keeps its own, as the core delivers them.
    const from = a >= 4 && bytes[a - 4] === 0 ? a - 4 : a - 3;
    pending.push([from, b]);
    if (type === 1 || type === 5) {
      const size = pending.reduce((n, [x, y]) => n + (y - x), 0);
      const unit = new Uint8Array(size);
      let at = 0;
      for (const [x, y] of pending) { unit.set(bytes.subarray(x, y), at); at += y - x; }
      out.push({ unit, key: type === 5 });
      pending = [];
    }
  }
  return out;
}

async function preview(url) {
  if (!window.VideoDecoder) return say(t("live_cannot_decode"), true);
  if (!url) return say("?clip=<recording in Annex B>", false);
  const bytes = new Uint8Array(await (await fetch(url)).arrayBuffer());
  const frames = accessUnits(bytes);
  name = "Preview";
  bar.hidden = false;
  let index = 0;
  setInterval(() => {
    const { unit, key } = frames[index % frames.length];
    const packet = new Uint8Array(9 + unit.length);
    packet[0] = key ? 1 : 0;
    new DataView(packet.buffer).setBigUint64(1, BigInt(index * 33333));
    packet.set(unit, 9);
    // The loop starts again at the first keyframe, so the decoder is told to flush there.
    if (index > 0 && index % frames.length === 0) packet[0] |= 2;
    onFrame(packet.buffer);
    index++;
  }, 33);
}


// ---- Clicking and typing on the phone ---------------------------------------------------------------

const controlButton = document.getElementById("control");
const help = document.getElementById("help");

function showControl() {
  if (kind !== "screen") return;
  controlButton.hidden = false;
  controlButton.classList.toggle("on", controlGranted && controlOn);
  controlButton.classList.toggle("off", !controlGranted);
  const label = controlGranted
    ? t(controlOn ? (computerPeer ? "live_control_stop_pc" : "live_control_stop") : (computerPeer ? "live_control_start_pc" : "live_control_start"))
    : t(computerPeer ? "live_control_not_allowed_pc" : "live_control_not_allowed");
  controlButton.title = label;
  controlButton.setAttribute("aria-label", label);
}

const active = () => controlGranted && controlOn && phoneRotation + extraRotation === 0;
const sendInput = (input) => { if (tauri) call("live_input", { session, input }).catch(() => {}); };

/** Where on the picture the pointer is, as fractions of it, or nothing when it is outside it. */
function fractionOf(event) {
  const box = canvas.getBoundingClientRect();
  if (!canvas.width || !canvas.height || !box.width || !box.height) return null;
  const scale = Math.min(box.width / canvas.width, box.height / canvas.height);
  const w = canvas.width * scale;
  const h = canvas.height * scale;
  const x = (event.clientX - box.left - (box.width - w) / 2) / w;
  const y = (event.clientY - box.top - (box.height - h) / 2) / h;
  if (x < 0 || y < 0 || x > 1 || y > 1) return null;
  return { x, y };
}

controlButton.addEventListener("click", () => {
  if (!controlGranted) {
    document.getElementById("helptext").innerHTML = "";
    const lines = computerPeer
      ? [t("live_pc_help"), t("live_pc_step_mac"), t("live_pc_step_other"), t("live_pc_wakes")]
      : [t("live_control_help"), t("live_control_step1"), t("live_control_step2"), t("live_control_step3"), t("live_control_wakes")];
    for (const line of lines) {
      const p = document.createElement("div");
      p.textContent = line;
      document.getElementById("helptext").appendChild(p);
    }
    help.hidden = false;
    return;
  }
  controlOn = !controlOn;
  showControl();
  say(t(controlOn ? (computerPeer ? "live_control_is_on_pc" : "live_control_is_on") : (computerPeer ? "live_control_is_off_pc" : "live_control_is_off")));
  setTimeout(() => { if (hasPicture) clear(); }, 1800);
});
document.getElementById("helpclose").addEventListener("click", () => { help.hidden = true; });

let pressed = false;
canvas.addEventListener("mousedown", (e) => {
  const at = active() && e.button === 0 ? fractionOf(e) : null;
  if (!at) return;
  pressed = true;
  sendInput({ t: "pointer", ...at });
  sendInput({ t: "button", button: 0, down: true, clicks: Math.max(1, e.detail) });
});
canvas.addEventListener("mousemove", (e) => {
  if (!pressed) return;
  const at = fractionOf(e);
  if (at) sendInput({ t: "pointer", ...at });
});
window.addEventListener("mouseup", (e) => {
  if (!pressed || e.button !== 0) return;
  pressed = false;
  const at = fractionOf(e);
  if (at) sendInput({ t: "pointer", ...at });
  sendInput({ t: "button", button: 0, down: false, clicks: Math.max(1, e.detail) });
});
// The right button is Back on the phone.
canvas.addEventListener("contextmenu", (e) => {
  if (!active() || !fractionOf(e)) return;
  e.preventDefault();
  sendInput({ t: "button", button: 1, down: true, clicks: 1 });
  sendInput({ t: "button", button: 1, down: false, clicks: 1 });
});
// The wheel and two fingers on the pad move the content the way fingers do.
canvas.addEventListener("wheel", (e) => {
  const at = active() ? fractionOf(e) : null;
  if (!at) return;
  e.preventDefault();
  const unit = e.deltaMode === 1 ? 16 : 1;
  const dx = Math.round(-e.deltaX * unit);
  const dy = Math.round(-e.deltaY * unit);
  if (!dx && !dy) return;
  sendInput({ t: "pointer", ...at });
  sendInput({ t: "scroll", dx, dy });
}, { passive: false });
// Letters and digits are typed; Escape is Back, Backspace takes a character off, Enter goes in as a line break.
window.addEventListener("keydown", (e) => {
  if (!active() || e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key === "Escape") sendInput({ t: "key", code: 0x29, down: true });
  else if (e.key === "Backspace") sendInput({ t: "key", code: 0x2a, down: true });
  else if (e.key === "Enter") sendInput({ t: "key", code: 0x28, down: true });
  else if (e.key.length === 1) sendInput({ t: "text", text: e.key });
  else return;
  e.preventDefault();
});

start().catch((e) => say(String(e), true));
