// The part Devices of Settings, also called Circle (P6): what each device of the circle may do on this computer. Four kinds, each a
// card with the devices as round switches, a sentence that says it in words, and the devices with their level. The core checks it.
import { html, useEffect, useState } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { DeviceGlyph, Switch, platformIcon } from "./components.js";
import { Icon } from "./icons.js";
import { t } from "./i18n.js";
import { say, set, state } from "./store.js";

const failed = (e) => say(String(e && e.message ? e.message : e));

// Only what this computer can be asked: calls, text messages and a restart from afar are things of a phone or a Mac.
const GROUPS = [
  { id: "see", icon: "clipboard", perms: ["clipboard", "see_notifications"] },
  { id: "files", icon: "folder-open", perms: ["browse_files", "files_without_asking", "delete_files"] },
  { id: "control", icon: "pointer", perms: ["control", "unlock", "lock"] },
  { id: "wifi", icon: "wifi", perms: ["wifi_password"] },
];
const LEVELS = ["own", "limited", "send"];

/** "all", "some" or "none" of a kind is allowed. */
function groupState(denied, group) {
  const off = group.perms.filter((p) => denied.includes(p)).length;
  return off === 0 ? "all" : off === group.perms.length ? "none" : "some";
}

/** "Pixel 9 and MacBook Pro", in the language of the window. */
function names(list) {
  return list.length <= 1 ? list.join("") : list.slice(0, -1).join(", ") + t("circle_and") + list[list.length - 1];
}

/** For a device without file choices of its own: which file permissions the default for all devices has off, in words. */
function followsDefault(entry) {
  const off = entry.defaultOff || [];
  return off.length === 0 ? null : t("circle_follows_default", names(off.map((p) => t("perm_" + p).toLowerCase())));
}

/** What a device lets this computer do, in a few words. */
function letsYou(entry) {
  if (!entry.allowsMe) return t("circle_lets_unknown");
  if (entry.allowsMe.length === 0) return t("circle_lets_all");
  return t("circle_lets_not", entry.allowsMe.map((p) => t("perm_" + p).toLowerCase()).join(", "));
}

function Level({ level }) {
  return html`<span class=${"circle-level " + level}>${t("level_" + level)}</span>`;
}

export function CircleSettings() {
  const [trust, setTrust] = useState(null);
  const [details, setDetails] = useState(null);
  const others = state.devices.filter((d) => !state.self || d.id !== state.self.id);
  // Asked again when a device comes or goes, or changes its table.
  useEffect(() => { call("trust_list").then(setTrust).catch(() => {}); }, [state.devices]);
  const entry = (id) => (trust || []).find((e) => e.id === id) || { id, denied: [], level: "own", allowsMe: null };
  const change = (id, permission, allowed) => call("trust_set", { id, permission, allowed }).then(setTrust).catch(failed);
  const level = (id, value) => call("trust_set_level", { id, level: value }).then(setTrust).catch(failed);
  const toggleGroup = (d, group) => {
    const on = groupState(entry(d.id).denied, group) !== "all";
    group.perms.reduce((done, p) => done.then(() => call("trust_set", { id: d.id, permission: p, allowed: on })), Promise.resolve())
      .then(() => call("trust_list")).then(setTrust).catch(failed);
  };

  const byLevel = (l) => others.filter((d) => entry(d.id).level === l).map((d) => d.name);
  const parts = [["own", "circle_says_own"], ["limited", "circle_says_limited"], ["send", "circle_says_send"], ["custom", "circle_says_custom"]]
    .filter(([l]) => byLevel(l).length > 0)
    .map(([l, key]) => html`<b>${names(byLevel(l))}</b>${" " + t(byLevel(l).length > 1 ? key + "_many" : key) + " "}`);

  const open = details && others.find((d) => d.id === details);
  return html`<div style="display:flex;flex-direction:column;gap:12px">
    <h2 class="circle-title">${t("tab_devices")}<span class="circle-name">${t("circle")}</span></h2>
    ${trust === null ? null : others.length === 0
      ? html`<div class="card"><div class="muted">${t("circle_empty")}</div></div>`
      : html`<p class="circle-summary">${parts}</p>
        <div class="circle-grid">${GROUPS.map((g) => html`<div key=${g.id} class=${"circle-tile g-" + g.id}>
          <div class="ch"><span class="ic"><${Icon} name=${g.icon} size=${18} /></span>
            <div class="grow"><div class="q">${t("circle_q_" + g.id)}</div><div class="what">${g.perms.map((p) => t("perm_" + p)).join(" · ")}</div></div></div>
          <div class="circle-faces">${others.map((d) => {
            const st = groupState(entry(d.id).denied, g);
            return html`<button key=${d.id} class=${"circle-face " + (st === "all" ? "yes" : st === "some" ? "some" : "no")}
              title=${d.name + ": " + t(st === "all" ? "circle_allowed" : st === "some" ? "circle_partly" : "circle_not_allowed")}
              aria-pressed=${st !== "none"} onClick=${() => toggleGroup(d, g)}>
              <span class="disc"><${Icon} name=${platformIcon(d.platform)} size=${20} /></span><span class="n">${d.name}</span></button>`;
          })}</div></div>`)}</div>
        <div class="card flush">${others.map((d) => {
          const e = entry(d.id);
          return html`<div key=${d.id} class="setting circle-device">
            <${DeviceGlyph} device=${d} size=${36} />
            <div class="grow"><div class="t">${d.name}</div><div class="s">${letsYou(e)}</div>
              ${followsDefault(e) && html`<div class="s circle-default">${followsDefault(e)}</div>`}</div>
            <${Level} level=${e.level} />
            <button class="btn small" onClick=${() => setDetails(d.id)}>${t("circle_details")}</button>
            <button class="iconbtn danger" title=${t("remove")} onClick=${() => set({ dialog: { kind: "remove", id: d.id } })}><${Icon} name="trash" size=${16} /></button>
          </div>`;
        })}</div>`}
    <div class="card circle-join">
      <span class="plusdisc big"><${Icon} name="plus" size=${20} /></span>
      <div class="grow"><div class="t">${t("add_device")}</div><div class="s">${t("circle_join_sub")}</div></div>
      <button class="btn accent" onClick=${() => set({ dialog: { kind: "pair" } })}><${Icon} name="qrcode" size=${16} />${t("add_device")}</button>
    </div>
    ${open && html`<div class="scrim" onClick=${(ev) => ev.target === ev.currentTarget && setDetails(null)}>
      <div class="dialog wide circle-details" role="dialog" aria-label=${open.name}>
        <div class="circle-details-head"><${DeviceGlyph} device=${open} size=${40} /><h2 class="grow">${open.name}</h2><${Level} level=${entry(open.id).level} /></div>
        <div class="circle-levels"><div class="seg">${LEVELS.map((l) => html`<button key=${l} class=${entry(open.id).level === l ? "on" : ""} onClick=${() => level(open.id, l)}>${t("level_" + l)}</button>`)}</div>
          <span class="small muted">${t(entry(open.id).level === "custom" ? "level_custom_sub" : "level_" + entry(open.id).level + "_sub")}</span></div>
        ${followsDefault(entry(open.id)) && html`<p class="small muted circle-default">${followsDefault(entry(open.id))}</p>`}
        <div class="circle-groups">${GROUPS.map((g) => html`<div key=${g.id} class=${"circle-group g-" + g.id}>
          <div class="ph"><${Icon} name=${g.icon} size=${16} />${t("circle_g_" + g.id)}</div>
          ${g.perms.map((p) => html`<div key=${p} class="circle-perm"><span class="grow">${t("perm_" + p)}</span>
            <${Switch} on=${!entry(open.id).denied.includes(p)} onChange=${(v) => change(open.id, p, v)} label=${t("perm_" + p)} /></div>`)}
        </div>`)}</div>
        <div style="display:flex;justify-content:flex-end;margin-top:14px"><button class="btn accent" onClick=${() => setDetails(null)}>${t("done")}</button></div>
      </div></div>`}
  </div>`;
}
