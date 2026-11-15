// Three examples of the page Circle (P6) for Mark to choose from. Plain DOM over the styles of the app, with made-up devices.
// Not part of the app and not for release. ?design=a|b|c, ?lang=en|nl, ?theme=light|dark, ?shot leaves out the switcher;
// ?center=, ?open= and ?dialog= open a state for a screenshot.
import { iconMarkup as ic } from "../js/icons.js";

const q = new URLSearchParams(location.search);
let design = q.get("design") || "a";
let lang = q.get("lang") === "nl" ? "nl" : "en";
let theme = q.get("theme");
const shot = q.has("shot");
document.documentElement.dataset.platform = q.get("platform") || "windows";

const PERMS = ["clipboard", "see_notifications", "browse_files", "delete_files", "files_without_asking", "control", "unlock", "lock",
  "restart", "calls", "messages", "wifi_password"];
const GROUPS = [
  { id: "see", icon: "clipboard", perms: ["clipboard", "see_notifications"] },
  { id: "files", icon: "folder-open", perms: ["browse_files", "files_without_asking", "delete_files"] },
  { id: "control", icon: "pointer", perms: ["control", "unlock", "lock", "restart"] },
  { id: "phone", icon: "phone", perms: ["calls", "messages", "wifi_password"] },
];
const LEVELS = { own: [], limited: ["delete_files", "unlock", "control", "wifi_password"], send: PERMS };

const T = {
  en: {
    circle: "Circle", devices: "Devices", connected_of: (a, b) => `${a} of ${b} connected`, connected: "Connected", away: "Not connected",
    shared: "Shared", messages_nav: "Messages", clipboard_nav: "Clipboard", notifications_nav: "Notifications", settings: "Settings",
    add_device: "Add device", this_device: "This device",
    p: { clipboard: "Clipboard", see_notifications: "See notifications", browse_files: "Browse files", delete_files: "Delete files",
      files_without_asking: "Take files without asking", control: "Mouse and keyboard", unlock: "Unlock", lock: "Lock", restart: "Restart Tandem",
      calls: "Calls", messages: "Text messages", wifi_password: "Wi-Fi password" },
    g: { see: "See", files: "Files", control: "Control", phone: "Phone and Wi-Fi" },
    gq: { see: "Sees what you copy and your notifications", files: "Opens and changes your files", control: "Controls this laptop",
      phone: "Asks for your Wi-Fi and phone" },
    lv: { own: "Own device", limited: "Limited", send: "Send only", custom: "Own choice", unknown: "Older version" },
    lvd: { own: "Everything, as now", limited: "No deleting, unlocking, control or Wi-Fi password", send: "Only sends things here, and you are asked" },
    lets_you: "Lets you", lets_all: "everything", lets_unknown: "unknown, older version",
    lets_not: (list) => "everything but " + list,
    a_lead: "Tap a device to put it in the middle. The lines are what it lets the others do.",
    a_allows: (n) => `What ${n} allows`, a_gets: (n) => `What ${n} may do`,
    a_panel: (n) => `What ${n} lets each device do`, a_panel_in: (n) => `What each device lets ${n} do`,
    some: "Part of it", unknown: "Unknown",
    change_on: (n) => `Change on ${n}`, opened_on: (n) => `Tandem opens its Circle on ${n}`,
    remove: (n) => `Remove ${n}`, remove_q: (n) => `Remove ${n} from the circle?`,
    remove_d: "It can no longer connect to any of your devices. To come back it has to be added again.", cancel: "Cancel", remove_ok: "Remove",
    removed: (n) => `${n} was removed`,
    b_lead: (n) => `Who may do what on ${n}. Tap a device to change it.`,
    c_lead_all: (names) => `<b>${names}</b> may do everything.`, c_lead_lim: (names) => ` <b>${names}</b> may not delete, unlock or control.`,
    c_lead_send: (names) => ` <b>${names}</b> may only send.`, c_lead_custom: (names) => ` <b>${names}</b> has a choice of its own.`,
    c_your: "Your devices", details: "Details", c_tap: "Tap a device to switch it on or off",
    join_t: "Add a device", join_d: "Open Tandem on the new device and scan the code, or type it in.", starts_as: "When it joins it may:",
    join_code: "Scan with the new device", done: "Done", new_device: "New phone", joined: (n) => `${n} joined as ${n === "" ? "" : ""}`,
    custom_hint: "Set each one yourself below.", example: "Example, not in the app",
  },
  nl: {
    circle: "Circle", devices: "Apparaten", connected_of: (a, b) => `${a} van ${b} verbonden`, connected: "Verbonden", away: "Niet verbonden",
    shared: "Gedeeld", messages_nav: "Berichten", clipboard_nav: "Klembord", notifications_nav: "Meldingen", settings: "Instellingen",
    add_device: "Apparaat toevoegen", this_device: "Dit apparaat",
    p: { clipboard: "Klembord", see_notifications: "Meldingen zien", browse_files: "Bestanden bekijken", delete_files: "Bestanden wissen",
      files_without_asking: "Bestanden zonder vragen", control: "Muis en toetsenbord", unlock: "Ontgrendelen", lock: "Vergrendelen",
      restart: "Tandem herstarten", calls: "Bellen", messages: "Sms'jes", wifi_password: "Wifi-wachtwoord" },
    g: { see: "Zien", files: "Bestanden", control: "Bedienen", phone: "Telefoon en wifi" },
    gq: { see: "Ziet wat je kopieert en je meldingen", files: "Opent en wijzigt je bestanden", control: "Bedient deze laptop",
      phone: "Vraagt je wifi en telefoon" },
    lv: { own: "Eigen apparaat", limited: "Beperkt", send: "Alleen sturen", custom: "Eigen keuze", unknown: "Oudere versie" },
    lvd: { own: "Alles, zoals nu", limited: "Geen wissen, ontgrendelen, bedienen of wifi-wachtwoord", send: "Stuurt alleen iets hierheen, en je krijgt de vraag" },
    lets_you: "Laat jou", lets_all: "alles", lets_unknown: "onbekend, oudere versie",
    lets_not: (list) => "alles behalve " + list,
    a_lead: "Tik op een apparaat om het in het midden te zetten. De lijnen zijn wat het de anderen laat doen.",
    a_allows: (n) => `Wat ${n} toestaat`, a_gets: (n) => `Wat ${n} mag`,
    a_panel: (n) => `Wat ${n} elk apparaat laat doen`, a_panel_in: (n) => `Wat elk apparaat ${n} laat doen`,
    some: "Een deel", unknown: "Onbekend",
    change_on: (n) => `Wijzig op ${n}`, opened_on: (n) => `Tandem opent zijn Circle op ${n}`,
    remove: (n) => `${n} verwijderen`, remove_q: (n) => `${n} uit de circle halen?`,
    remove_d: "Het kan dan met geen van je apparaten meer verbinden. Om terug te komen moet het opnieuw worden toegevoegd.",
    cancel: "Annuleren", remove_ok: "Verwijderen", removed: (n) => `${n} is verwijderd`,
    b_lead: (n) => `Wie wat mag op ${n}. Tik op een apparaat om het te wijzigen.`,
    c_lead_all: (names) => `<b>${names}</b> mogen alles.`, c_lead_lim: (names) => ` <b>${names}</b> mag niet wissen, ontgrendelen of bedienen.`,
    c_lead_send: (names) => ` <b>${names}</b> mag alleen sturen.`, c_lead_custom: (names) => ` <b>${names}</b> heeft een eigen keuze.`,
    c_your: "Je apparaten", details: "Details", c_tap: "Tik op een apparaat om het aan of uit te zetten",
    join_t: "Apparaat toevoegen", join_d: "Open Tandem op het nieuwe apparaat en scan de code, of typ hem over.", starts_as: "Als het erbij komt mag het:",
    join_code: "Scan met het nieuwe apparaat", done: "Klaar", new_device: "Nieuwe telefoon",
    custom_hint: "Zet ze hieronder elk zelf.", example: "Voorbeeld, niet in de app",
  },
};
const t = () => T[lang];

// ---- Made-up devices and who lets whom do what ----
const ME = "me";
let devices = [
  { id: ME, name: "My Laptop", platform: "windows", online: true, battery: 92 },
  { id: "phone", name: "Pixel 9", platform: "android", online: true, battery: 64 },
  { id: "mac", name: "MacBook Pro", platform: "macos", online: true, battery: 88 },
  { id: "linux", name: "ThinkPad", platform: "linux", online: false },
  { id: "tab", name: "Galaxy Tab", platform: "android", online: true, battery: 31 },
  { id: "old", name: "Xperia test", platform: "android", online: false, old: true },
];
// "from>to": what `from` does not let `to` do. Missing: `from` is of a version without permissions.
const denied = {};
const set = (from, to, list) => { denied[from + ">" + to] = new Set(list); };
for (const a of devices) for (const b of devices) if (a.id !== b.id && !a.old) set(a.id, b.id, []);
set(ME, "linux", LEVELS.limited);
set(ME, "tab", LEVELS.send);
set(ME, "old", ["see_notifications", "control"]);
set("phone", "tab", LEVELS.send);
set("mac", "tab", LEVELS.send);
set("linux", "tab", LEVELS.send);
set("phone", "linux", LEVELS.limited);
set("mac", "linux", LEVELS.limited);

const dev = (id) => devices.find((d) => d.id === id);
const others = (id) => devices.filter((d) => d.id !== id);
const has = (from, to) => denied[from + ">" + to] !== undefined;
function levelOf(set) {
  if (!set) return "unknown";
  for (const [name, list] of Object.entries(LEVELS)) if (list.length === set.size && list.every((p) => set.has(p))) return name;
  return "custom";
}
function groupState(set, g) {
  if (!set) return "unknown";
  const off = g.perms.filter((p) => set.has(p)).length;
  return off === 0 ? "all" : off === g.perms.length ? "none" : "some";
}
const platformIcon = (p) => ({ android: "device-mobile", macos: "device-laptop", linux: "device-laptop", windows: "device-laptop" })[p] || "devices";
const glyph = (d, size = 40) => `<span class="glyph${d.online ? " on" : ""}" style="width:${size}px;height:${size}px">${ic(platformIcon(d.platform), Math.round(size * 0.5))}</span>`;
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const lvl = (name) => `<span class="lvl ${name}">${t().lv[name]}</span>`;

function letsYou(from) {
  const s = denied[from + ">" + ME];
  if (!s) return `${t().lets_you}: ${t().lets_unknown}`;
  if (s.size === 0) return `${t().lets_you}: ${t().lets_all}`;
  const level = levelOf(s);
  if (level !== "custom") return `${t().lets_you}: ${t().lv[level].toLowerCase()}`;
  return `${t().lets_you}: ${t().lets_not([...s].map((p) => t().p[p].toLowerCase()).join(", "))}`;
}

function setPerm(to, perm, on) {
  const s = denied[ME + ">" + to];
  if (on) s.delete(perm); else s.add(perm);
}
function setGroup(to, g, on) { for (const p of g.perms) setPerm(to, p, on); }
function setLevel(to, level) { denied[ME + ">" + to] = new Set(LEVELS[level]); }

// ---- What is open ----
let center = q.get("center") || ME;
let direction = "allows";
let openRow = q.get("open");
let dialog = q.get("dialog") ? { kind: q.get("dialog"), id: q.get("open") } : null;
let toast = null;
let joinLevel = "own";
const lastPos = {};

function narrow() { return window.innerWidth < 900; }

// ---- The window around the page: the sidebar of the app, with Circle as a page of its own ----
function nav() {
  const list = others(ME);
  const online = list.filter((d) => d.online).length;
  const devRow = (d) => `<div class="row devrow${d.online ? "" : " dim"}">
      <span class="ringed${d.online && d.battery ? "" : " none"}" style="width:40px;height:40px;--level:${d.battery || 0}">${glyph(d, 34)}</span>
      <div class="words grow"><div class="name ellipsis">${esc(d.name)}</div>
        <div class="line2"><span class="sub ellipsis${d.online ? " ok" : ""}">${d.online ? t().connected : t().away}</span>${d.online && d.battery ? `<span class="small muted bat">${d.battery}%</span>` : ""}</div></div>
      <span class="mini">${esc(d.name.split(" ")[0])}</span></div>`;
  const page = (icon, label, on) => `<div class="row pagerow${on ? " selected" : ""}"><span class="navicon">${ic(icon, 20)}</span><span class="words grow ellipsis">${label}</span><span class="mini">${label}</span></div>`;
  return `<nav class="nav${narrow() ? " fold" : ""}">
    <div class="navhead"><span class="t">${t().devices}</span><span class="count"><i class="dot on"></i>${t().connected_of(online, list.length)}</span></div>
    ${list.map(devRow).join("")}
    <div class="navline"></div>
    ${page("files", t().shared)}${page("message", t().messages_nav)}${page("clipboard", t().clipboard_nav)}${page("bell", t().notifications_nav)}
    ${page("users", t().circle, true)}${page("settings", t().settings)}
    <div class="spacer"></div>
    <div class="row pagerow addrow" data-act="join"><span class="navicon"><span class="plusdisc">${ic("plus", 14)}</span></span><span class="words grow ellipsis">${t().add_device}</span></div>
  </nav>`;
}

function title(lead) {
  return `<div class="ctitle"><h1>${t().circle}</h1><p class="lead">${lead}</p></div>`;
}

function switches(to, editable) {
  const s = denied[ME + ">" + to];
  return `<div class="pgroups${editable ? "" : " ro"}">${GROUPS.map((g) => `<div class="pgroup g-${g.id}">
      <div class="ph">${ic(g.icon, 16)}${t().g[g.id]}</div>
      ${g.perms.map((p) => `<div class="prow"><span class="grow">${t().p[p]}</span>
        <button class="switch${s.has(p) ? "" : " on"}" data-act="perm" data-to="${to}" data-perm="${p}" aria-label="${t().p[p]}"></button></div>`).join("")}
    </div>`).join("")}</div>`;
}

function levelSeg(to) {
  const level = levelOf(denied[ME + ">" + to]);
  return `<div class="startas"><div class="seg levelseg">${["own", "limited", "send"].map((l) =>
    `<button class="${level === l ? "on" : ""}" data-act="level" data-to="${to}" data-level="${l}" title="${t().lvd[l]}">${t().lv[l]}</button>`).join("")}</div>
    <span class="small muted">${level === "custom" ? t().custom_hint : t().lvd[level]}</span></div>`;
}

function gdots(set, to, editable) {
  return `<span class="gdots">${GROUPS.map((g) => {
    const st = groupState(set, g);
    const cls = `gdot g-${g.id}${st === "some" ? " some" : st === "none" ? " none" : ""}`;
    const label = `${t().g[g.id]}: ${st === "all" ? "✓" : st === "some" ? t().some : "✗"}`;
    return editable ? `<button class="${cls}" title="${label}" data-act="group" data-to="${to}" data-group="${g.id}">${ic(g.icon, 15)}</button>`
      : `<span class="${cls}" title="${label}">${ic(g.icon, 15)}</span>`;
  }).join("")}</span>`;
}

function joinCard() {
  return `<div class="card joincard"><span class="plusbig">${ic("plus", 22)}</span>
    <div class="grow"><div style="font-weight:650">${t().join_t}</div><div class="small muted">${t().join_d}</div></div>
    <button class="btn accent" data-act="join">${ic("qrcode", 16)}${t().add_device}</button></div>`;
}

// ---- A: the ring. The device in the middle has a spoke to each other one, a line per kind of permission ----
function designA() {
  const c = dev(center);
  const ring = others(center);
  const n = ring.length + 1;
  const R = 37;
  const pos = {};
  pos[center] = [50, 50];
  ring.forEach((d, i) => {
    const a = -Math.PI / 2 + (i * 2 * Math.PI) / n;
    pos[d.id] = [50 + R * Math.cos(a), 50 + R * Math.sin(a)];
  });
  const addA = -Math.PI / 2 + (ring.length * 2 * Math.PI) / n;
  const addPos = [50 + R * Math.cos(addA), 50 + R * Math.sin(addA)];
  const pairOf = (d) => (direction === "allows" ? [center, d.id] : [d.id, center]);

  let svg = `<circle class="orbit" cx="50" cy="50" r="${R}" />`;
  // The rest of the web, faint: every device knows every table.
  for (let i = 0; i < ring.length; i++) for (let j = i + 1; j < ring.length; j++) {
    const [x1, y1] = pos[ring[i].id], [x2, y2] = pos[ring[j].id];
    svg += `<line class="chord" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" opacity="0.5" />`;
  }
  for (const d of ring) {
    const [x, y] = pos[d.id];
    const [from, to] = pairOf(d);
    const s = denied[from + ">" + to];
    const len = Math.hypot(x - 50, y - 50), nx = -(y - 50) / len, ny = (x - 50) / len;
    // Stop short of the discs.
    // Above the middle the name hangs between the disc and the spoke: stop before it.
    const k0 = 11 / len, k1 = 1 - (y < 45 ? 17 : 8) / len;
    const P = (k, off) => [50 + (x - 50) * k + nx * off, 50 + (y - 50) * k + ny * off];
    if (!s) {
      const [a1, b1] = P(k0, 0), [a2, b2] = P(k1, 0);
      svg += `<line class="unknownline" x1="${a1}" y1="${b1}" x2="${a2}" y2="${b2}" />`;
      continue;
    }
    // A device that may do nothing is still in the circle: a hairline keeps it joined.
    if (s.size === PERMS.length) {
      const [a1, b1] = P(k0, 0), [a2, b2] = P(k1, 0);
      svg += `<line class="base" x1="${a1}" y1="${b1}" x2="${a2}" y2="${b2}" />`;
    }
    GROUPS.forEach((g, gi) => {
      const st = groupState(s, g);
      const off = (gi - 1.5) * 1.15;
      const [a1, b1] = P(k0, off), [a2, b2] = P(k1, off);
      if (st === "none") return;
      svg += `<line class="spoke g-${g.id}${st === "some" ? " some" : ""}" x1="${a1}" y1="${b1}" x2="${a2}" y2="${b2}" />`;
    });
  }
  const node = (d) => {
    const p = lastPos[d.id] || pos[d.id];
    return `<button class="node${d.id === center ? " center" : ""}${d.online ? " online" : ""}" data-act="center" data-id="${d.id}" data-x="${pos[d.id][0]}" data-y="${pos[d.id][1]}"
      style="left:${p[0]}%;top:${p[1]}%"><span class="disc">${ic(platformIcon(d.platform), d.id === center ? 36 : 24)}</span>
      <span class="nm">${esc(d.name)}</span>${d.id === ME ? `<span class="st">${t().this_device}</span>` : d.old ? `<span class="st">${t().lv.unknown}</span>` : ""}</button>`;
  };
  const nodes = devices.map(node).join("") + `<button class="node add" data-act="join" style="left:${addPos[0]}%;top:${addPos[1]}%"><span class="disc">${ic("plus", 22)}</span><span class="nm">${t().add_device}</span></button>`;

  const editable = center === ME && direction === "allows";
  const rows = ring.map((d) => {
    const [from, to] = pairOf(d);
    const s = denied[from + ">" + to];
    const open = openRow === d.id && editable;
    return `<div class="arow${open ? " open" : ""}" data-act="${editable ? "open" : ""}" data-id="${d.id}">${glyph(d, 34)}
        <div class="grow"><div class="name ellipsis">${esc(d.name)}</div></div>
        ${lvl(levelOf(s))}${s ? gdots(s, d.id, editable) : ""}${editable ? ic(open ? "chevron-right" : "chevron-right", 16) : ""}</div>
      ${open ? `<div class="aopen">${levelSeg(d.id)}${switches(d.id, true)}</div>` : ""}`;
  }).join("");
  const foot = center === ME
    ? ""
    : `<button class="btn" data-act="openon" data-id="${center}">${ic("external-link", 16)}${t().change_on(esc(c.name))}</button>
       <span class="grow"></span><button class="btn ghost danger" data-act="remove" data-id="${center}">${ic("trash", 16)}${t().remove(esc(c.name))}</button>`;

  return `<div class="wrap">${title(t().a_lead)}
    <div class="a-layout">
      <div class="card ringcard">
        <div class="seg"><button class="${direction === "allows" ? "on" : ""}" data-act="dir" data-dir="allows">${t().a_allows(esc(c.name))}</button>
          <button class="${direction === "gets" ? "on" : ""}" data-act="dir" data-dir="gets">${t().a_gets(esc(c.name))}</button></div>
        <div class="ringbox"><svg class="web" viewBox="0 0 100 100">${svg}</svg>${nodes}</div>
        <div class="legend">${GROUPS.map((g) => `<span class="g-${g.id}"><i></i>${t().g[g.id]}</span>`).join("")}<span><i class="dash"></i>${t().some} / ${t().unknown.toLowerCase()}</span></div>
      </div>
      <div class="card flush apanel">
        <div class="phead">${glyph(c, 40)}<div class="grow"><div class="t">${direction === "allows" ? t().a_panel(esc(c.name)) : t().a_panel_in(esc(c.name))}</div>
          ${has(center, ring[0]?.id) || direction === "gets" ? "" : `<div class="small muted">${t().lets_unknown}</div>`}</div></div>
        ${rows}
        ${foot ? `<div class="pfoot">${foot}</div>` : ""}
      </div>
    </div></div>`;
}

// ---- B: a level per device, with the four kinds as marks; open one to change it ----
function designB() {
  const list = others(ME);
  const byLevel = (l) => list.filter((d) => levelOf(denied[ME + ">" + d.id]) === l);
  const tile = (l) => {
    const ds = byLevel(l);
    return `<div class="tile ${l}"><span class="tk">${t().lv[l]}</span><span class="big">${ds.length}</span>
      <span class="names">${ds.map((d) => `<span title="${esc(d.name)}">${glyph(d, 26)}</span>`).join("") || `<span class="ts">${t().lvd[l]}</span>`}</span></div>`;
  };
  const rows = list.map((d) => {
    const s = denied[ME + ">" + d.id];
    const open = openRow === d.id;
    return `<div class="bdev" data-act="open" data-id="${d.id}">
        <span class="ringed${d.online && d.battery ? "" : " none"}" style="width:44px;height:44px;--level:${d.battery || 0}">${glyph(d, 38)}</span>
        <div class="grow"><div class="name ellipsis">${esc(d.name)}</div><div class="sub ellipsis">${letsYou(d.id)}</div></div>
        ${gdots(s, d.id, false)}${lvl(levelOf(s))}${ic("chevron-right", 16)}</div>
      ${open ? `<div class="bopen">${levelSeg(d.id)}${switches(d.id, true)}
        <div style="display:flex"><button class="btn ghost danger" data-act="remove" data-id="${d.id}">${ic("trash", 16)}${t().remove(esc(d.name))}</button></div></div>` : ""}`;
  }).join(`<div class="navline" style="margin:2px 12px"></div>`);
  return `<div class="wrap">${title(t().b_lead(esc(dev(ME).name)))}
    <div class="tiles b-tiles">${tile("own")}${tile("limited")}${tile("send")}</div>
    <div class="card flush">${rows}</div>
    ${joinCard()}</div>`;
}

// ---- C: by permission. Four questions, and under each the devices that may ----
function designC() {
  const list = others(ME);
  const names = (l) => list.filter((d) => levelOf(denied[ME + ">" + d.id]) === l).map((d) => esc(d.name));
  const join = (ns) => (ns.length <= 1 ? ns.join("") : ns.slice(0, -1).join(", ") + (lang === "nl" ? " en " : " and ") + ns[ns.length - 1]);
  let summary = "";
  if (names("own").length) summary += t().c_lead_all(join(names("own")));
  if (names("limited").length) summary += t().c_lead_lim(join(names("limited")));
  if (names("send").length) summary += t().c_lead_send(join(names("send")));
  if (names("custom").length) summary += t().c_lead_custom(join(names("custom")));
  const tiles = GROUPS.map((g) => {
    const faces = list.map((d) => {
      const st = groupState(denied[ME + ">" + d.id], g);
      const cls = st === "all" ? "yes" : st === "some" ? "some" : "no";
      return `<button class="cface ${cls}" data-act="group" data-to="${d.id}" data-group="${g.id}" title="${esc(d.name)}">
        <span class="disc">${ic(platformIcon(d.platform), 20)}</span><span class="n">${esc(d.name.split(" ")[0])}</span></button>`;
    }).join("");
    return `<div class="ctile g-${g.id}"><div class="ch"><span class="ic">${ic(g.icon, 18)}</span>
        <div class="grow"><div class="q">${t().gq[g.id]}</div><div class="what">${g.perms.map((p) => t().p[p]).join(" · ")}</div></div></div>
      <div class="cfaces">${faces}</div></div>`;
  }).join("");
  const rows = list.map((d) => {
    const s = denied[ME + ">" + d.id];
    return `<div class="cdev">${glyph(d, 38)}<div class="grow"><div class="name ellipsis">${esc(d.name)}</div><div class="sub ellipsis">${letsYou(d.id)}</div></div>
      ${lvl(levelOf(s))}<button class="btn small" data-act="details" data-id="${d.id}">${t().details}</button>
      <button class="iconbtn danger" data-act="remove" data-id="${d.id}" title="${t().remove(esc(d.name))}">${ic("trash", 16)}</button></div>`;
  }).join("");
  return `<div class="wrap">${title(t().c_tap)}<p class="c-summary">${summary}</p>
    <div class="c-grid">${tiles}</div>
    <h2>${t().c_your}</h2><div class="card flush">${rows}</div>
    ${joinCard()}</div>`;
}

function dialogs() {
  if (!dialog) return "";
  if (dialog.kind === "join") {
    return `<div class="scrim" data-act="close"><div class="dialog" data-stop>
      <h2>${t().join_t}</h2><p class="lead">${t().join_d}</p>
      <div style="display:flex;gap:16px;align-items:center;margin:10px 0 16px">
        <div style="width:132px;height:132px;border-radius:14px;background:var(--card-2);display:grid;place-items:center;color:var(--text-2);flex:none">${ic("qrcode", 96, 1.2)}</div>
        <div class="small muted">${t().join_code}<div style="font:600 22px var(--font-display);letter-spacing:3px;color:var(--text);margin-top:6px">482 913</div></div></div>
      <div style="font-weight:650;margin-bottom:8px">${t().starts_as}</div>
      <div class="seg levelseg">${["own", "limited", "send"].map((l) => `<button class="${joinLevel === l ? "on" : ""}" data-act="joinlevel" data-level="${l}">${t().lv[l]}</button>`).join("")}</div>
      <div class="small muted">${t().lvd[joinLevel]}</div>
      <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:18px"><button class="btn" data-act="close">${t().cancel}</button>
        <button class="btn accent" data-act="joined">${t().done}</button></div></div></div>`;
  }
  const d = dev(dialog.id);
  if (dialog.kind === "remove") {
    return `<div class="scrim" data-act="close"><div class="dialog" data-stop><h2>${t().remove_q(esc(d.name))}</h2><p class="lead">${t().remove_d}</p>
      <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px"><button class="btn" data-act="close">${t().cancel}</button>
      <button class="btn accent" style="background:var(--bad)" data-act="removed" data-id="${d.id}">${t().remove_ok}</button></div></div></div>`;
  }
  if (dialog.kind === "details") {
    return `<div class="scrim" data-act="close"><div class="dialog wide" data-stop>
      <div style="display:flex;align-items:center;gap:12px;margin-bottom:12px">${glyph(d, 40)}<h2 class="grow" style="margin:0">${esc(d.name)}</h2>${lvl(levelOf(denied[ME + ">" + d.id]))}</div>
      ${levelSeg(d.id)}<div style="height:10px"></div>${switches(d.id, true)}
      <div style="display:flex;justify-content:flex-end;margin-top:14px"><button class="btn accent" data-act="close">${t().done}</button></div></div></div>`;
  }
  return "";
}

function switcher() {
  if (shot) return "";
  const b = (act, val, on, label) => `<button class="${on ? "on" : ""}" data-act="${act}" data-val="${val}">${label}</button>`;
  return `<div class="switcher">${b("design", "a", design === "a", "A")}${b("design", "b", design === "b", "B")}${b("design", "c", design === "c", "C")}<span class="sep"></span>
    ${b("lang", "en", lang === "en", "EN")}${b("lang", "nl", lang === "nl", "NL")}<span class="sep"></span>
    ${b("theme", "light", theme !== "dark", "☀")}${b("theme", "dark", theme === "dark", "☾")}</div>`;
}

function render() {
  if (theme) document.documentElement.dataset.theme = theme;
  document.documentElement.lang = lang;
  const page = design === "b" ? designB() : design === "c" ? designC() : designA();
  document.getElementById("root").innerHTML = `<div id="app">${nav()}<main class="page cpage">${page}</main></div>${dialogs()}
    ${toast ? `<div class="toast">${toast}</div>` : ""}${switcher()}`;
  // The devices of the ring glide from where they were to where they are now.
  if (design === "a") {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      for (const el of document.querySelectorAll(".node[data-id]")) {
        el.style.left = el.dataset.x + "%";
        el.style.top = el.dataset.y + "%";
        lastPos[el.dataset.id] = [+el.dataset.x, +el.dataset.y];
      }
    }));
  }
}

function say(text) {
  toast = text;
  render();
  setTimeout(() => { toast = null; render(); }, 2200);
}

document.addEventListener("click", (e) => {
  const el = e.target.closest("[data-act]");
  if (!el) return;
  if (el.dataset.act === "close" && e.target.closest("[data-stop]") && !e.target.closest("button")) return;
  const { act, id, to } = el.dataset;
  switch (act) {
    case "design": design = el.dataset.val; openRow = null; break;
    case "lang": lang = el.dataset.val; break;
    case "theme": theme = el.dataset.val; break;
    case "center": center = id; openRow = null; break;
    case "dir": direction = el.dataset.dir; openRow = null; break;
    case "open": openRow = openRow === id ? null : id; break;
    case "perm": setPerm(to, el.dataset.perm, !el.classList.contains("on")); break;
    case "group": {
      if (design === "a" && (center !== ME || direction !== "allows")) return;
      const g = GROUPS.find((x) => x.id === el.dataset.group);
      setGroup(to, g, groupState(denied[ME + ">" + to], g) !== "all");
      e.stopPropagation();
      break;
    }
    case "level": setLevel(to, el.dataset.level); break;
    case "details": dialog = { kind: "details", id }; break;
    case "join": dialog = { kind: "join" }; break;
    case "joinlevel": joinLevel = el.dataset.level; break;
    case "joined": {
      const nid = "new" + devices.length;
      devices.push({ id: nid, name: t().new_device, platform: "android", online: true, battery: 100 });
      for (const d of devices) if (d.id !== nid) { set(nid, d.id, []); if (!d.old) set(d.id, nid, d.id === ME ? LEVELS[joinLevel] : []); }
      dialog = null;
      break;
    }
    case "remove": dialog = { kind: "remove", id }; break;
    case "removed": {
      const name = dev(id).name;
      devices = devices.filter((d) => d.id !== id);
      if (center === id) center = ME;
      dialog = null;
      say(t().removed(esc(name)));
      return;
    }
    case "openon": say(t().opened_on(esc(dev(id).name))); return;
    case "close": dialog = null; break;
    default: return;
  }
  e.stopPropagation();
  render();
});
let resizeTimer;
window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 80); });
render();
