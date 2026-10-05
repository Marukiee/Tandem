// The window of one phone's screen or camera. The frames arrive from the Rust side as H.264 access units in Annex B and
// are decoded by the browser engine (WebCodecs), which uses the graphics card where it can, and drawn on a canvas.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { ResizeEdges, WindowButtons } from "./chrome.js";
import { iconMarkup } from "./icons.js";
import { setPlatform, t } from "./i18n.js";
import { set } from "./store.js";

const tauri = window.__TAURI__;
const session = Number(new URLSearchParams(location.search).get("session"));
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
let ended = false;
let hasPicture = false;
let lastAsk = 0;
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
  if (ended) return;
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

const reasons = {
  Denied: "live_denied",
  Unavailable: "live_unavailable",
  Unsupported: "live_unsupported",
  Timeout: "live_timeout",
};

function finish(reason) {
  ended = true;
  hasPicture = false;
  const key = reasons[reason];
  say(key ? t(key, name) : t("live_ended"), true);
  bar.hidden = true;
}

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
  closeButton.textContent = text("close");

  // In a plain browser, which is how this window is tried while it is being made, a recording stands in for the phone.
  if (!tauri) return preview(new URLSearchParams(location.search).get("clip"));
  const channel = new tauri.core.Channel();
  channel.onmessage = (buffer) => { onFrame(buffer instanceof ArrayBuffer ? buffer : new Uint8Array(buffer).buffer); };
  const info = await call("live_attach", { session, onFrame: channel });
  setPlatform(info.platform);
  set({ platform: info.platform });
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
  if (info.ended) return finish(info.ended);
  say(t(kind === "camera" ? "live_waiting_camera" : "live_waiting_screen", name));
  bar.hidden = false;
}

listen("live-accepted", () => { if (!hasPicture) say(t("live_first_picture")); });
listen("live-update", (update) => { if (typeof update.rotation === "number") phoneRotation = update.rotation; });
listen("live-ended", (event) => finish(event.reason));

document.getElementById("pin").addEventListener("click", (e) => {
  pinned = !pinned;
  e.currentTarget.classList.toggle("on", pinned);
  call("live_pin", { session, on: pinned });
});
document.getElementById("turn").addEventListener("click", () => { extraRotation = (extraRotation + 90) % 360; });
document.getElementById("copy").addEventListener("click", () => {
  canvas.toBlob((blob) => { if (blob) navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]).catch(() => {}); });
});
document.getElementById("stop").addEventListener("click", () => { call("live_stop", { session }).finally(() => window.close()); });
closeButton.addEventListener("click", () => { call("live_stop", { session }).finally(() => window.close()); });
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

start().catch((e) => say(String(e), true));
