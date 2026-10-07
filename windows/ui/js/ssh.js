// The window of one login to another computer (see src-tauri/src/ssh.rs). It asks who to log in as, starts the login, and then
// is a terminal: what the computer prints is written to it, and what is typed goes back.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen, native } from "./backend.js";
import { ResizeEdges, WindowButtons } from "./chrome.js";
import { setPlatform, t } from "./i18n.js";
import { set } from "./store.js";

const params = new URLSearchParams(location.search);
const id = params.get("id") || "";
const name = params.get("name") || "";
const address = params.get("address") || "";
const label = native ? window.__TAURI__.window.getCurrentWindow().label : "ssh-preview";

const ask = document.getElementById("ask");
const user = document.getElementById("user");
const problem = document.getElementById("problem");
const holder = document.getElementById("term");

async function start() {
  const info = await call("ssh_info", { label }).catch(() => ({ platform: "windows" }));
  setPlatform(info.platform);
  set({ platform: info.platform });
  document.title = name;
  document.getElementById("asklabel").textContent = t("ssh_login_as", name);
  document.getElementById("go").textContent = t("ssh_login");
  let remembered = "";
  try { remembered = localStorage.getItem("sshUser." + id) || ""; } catch (e) { /* no storage: it is asked again */ }
  user.value = remembered;
  user.focus();
  if (info.platform === "linux") {
    render(html`<${WindowButtons} />`, document.getElementById("chrome"));
    render(html`<${ResizeEdges} />`, document.getElementById("edges"));
  }
}

ask.addEventListener("submit", async (event) => {
  event.preventDefault();
  const who = user.value.trim();
  if (!who) return;
  problem.textContent = "";
  try { localStorage.setItem("sshUser." + id, who); } catch (e) { /* kept for this time only */ }
  ask.hidden = true;
  holder.hidden = false;

  const term = new window.Terminal({
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, "DejaVu Sans Mono", monospace',
    fontSize: 13, cursorBlink: true, theme: { background: "#121216", foreground: "#ececf2" },
  });
  const fit = new window.FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(holder);
  fit.fit();
  if (!native) {
    term.write("Preview: " + who + "@" + address + "\r\n");
    return;
  }
  const channel = new window.__TAURI__.core.Channel();
  channel.onmessage = (buffer) => term.write(new Uint8Array(buffer instanceof ArrayBuffer ? buffer : new Uint8Array(buffer).buffer));
  let login;
  try {
    login = await call("ssh_start", { label, user: who, address, cols: term.cols, rows: term.rows, onOutput: channel });
  } catch (e) {
    holder.hidden = true;
    ask.hidden = false;
    problem.textContent = String(e);
    return;
  }
  term.onData((data) => call("ssh_write", { login, data }));
  term.onResize(({ cols, rows }) => call("ssh_resize", { login, cols, rows }));
  window.addEventListener("resize", () => fit.fit());
  listen("ssh-ended", () => term.write("\r\n[" + t("ssh_ended") + "]\r\n"));
  window.addEventListener("pagehide", () => call("ssh_close", { login }));
  term.focus();
});

start();
