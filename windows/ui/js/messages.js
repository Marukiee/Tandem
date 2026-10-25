// Messages: the text messages of a phone, the way Messages on a Mac shows those of an iPhone. The conversations on the left,
// the one that is open on the right, a field at the bottom. What is sent goes out from the phone, as an ordinary SMS.
import { html, useEffect, useRef, useState } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";
import { language, t } from "./i18n.js";
import { say, set, state as store } from "./store.js";

const PAGE = 40;
const REFUSALS = ["off", "no_permission", "unavailable", "offline"];

// What is known of each phone, kept while the window is open so going back and forth does not ask the phone again.
const phones = new Map();
const redraws = new Set();

function phone(id) {
  if (!phones.has(id)) phones.set(id, { conversations: [], more: false, load: "idle", problem: "", threads: new Map(), pending: [], pictures: new Map() });
  return phones.get(id);
}

function redraw() {
  redraws.forEach((r) => r());
}

const key = (m) => (m.mms ? "m" : "s") + m.id;

function mergeConversations(items, list) {
  const byThread = new Map(list.map((c) => [c.thread, c]));
  items.forEach((c) => byThread.set(c.thread, c));
  return [...byThread.values()].sort((a, b) => b.date - a.date || b.thread - a.thread);
}

function mergeMessages(items, list) {
  const byKey = new Map(list.map((m) => [key(m), m]));
  items.forEach((m) => byKey.set(key(m), m));
  return [...byKey.values()].sort((a, b) => b.date - a.date || b.id - a.id);
}

// Until when each conversation was looked at here. The unread count of the phone only changes when it is read there.
function seenAll() {
  try { return JSON.parse(localStorage.getItem("sms-seen") || "{}"); } catch { return {}; }
}

function seen(device, thread) {
  return (seenAll()[device] || {})[thread] || 0;
}

function markSeen(device, conversation) {
  if (!conversation || conversation.date <= seen(device, conversation.thread)) return;
  const all = seenAll();
  all[device] = { ...(all[device] || {}), [conversation.thread]: conversation.date };
  try { localStorage.setItem("sms-seen", JSON.stringify(all)); } catch { /* without storage the dot comes back next time */ }
}

/** A text written here is done waiting once the phone shows it going out in that conversation. */
function settle(p, thread) {
  const mine = (p.threads.get(thread)?.messages || []).filter((m) => m.status !== "received");
  p.pending = p.pending.filter((item) => item.state === "failed" || item.thread !== thread
    // The clock of the phone may be a little off from this one.
    || !mine.some((m) => m.body === item.text && m.date >= item.date - 30000));
}

let following = false;
function follow() {
  if (following) return;
  following = true;
  listen("sms", ({ device, conversations, messages }) => {
    const p = phones.get(device);
    // Before the first page there is nothing to keep up to date: opening the page asks for everything.
    if (!p || p.load !== "ready") return;
    p.conversations = mergeConversations(conversations, p.conversations);
    const threads = new Set(messages.map((m) => m.thread));
    threads.forEach((thread) => {
      const state = p.threads.get(thread);
      if (!state) return;
      state.messages = mergeMessages(messages.filter((m) => m.thread === thread), state.messages);
      settle(p, thread);
    });
    if (p.open != null) markSeen(device, p.conversations.find((c) => c.thread === p.open));
    redraw();
  });
}

async function refresh(device) {
  const p = phone(device);
  if (!p.conversations.length) { p.load = "loading"; redraw(); }
  try {
    const page = await call("sms_conversations", { id: device, before: 0, limit: PAGE });
    p.conversations = mergeConversations(page.items, []);
    p.more = page.more;
    p.load = "ready";
    if (p.open != null) await loadThread(device, p.open, true);
  } catch (e) {
    const code = String(e);
    p.load = REFUSALS.includes(code) ? code : "failed";
    p.problem = code;
  }
  redraw();
}

async function moreConversations(device) {
  const p = phone(device);
  const last = p.conversations[p.conversations.length - 1];
  if (!p.more || p.loadingMore || !last) return;
  p.loadingMore = true;
  try {
    const page = await call("sms_conversations", { id: device, before: last.date, limit: PAGE });
    const before = p.conversations.length;
    p.conversations = mergeConversations(page.items, p.conversations);
    p.more = page.more && p.conversations.length > before;
  } catch { /* the next scroll tries again */ }
  p.loadingMore = false;
  redraw();
}

async function loadThread(device, thread, again = false) {
  const p = phone(device);
  const state = p.threads.get(thread) || { messages: [], more: true, loading: false, failed: "" };
  p.threads.set(thread, state);
  if (state.messages.length && !again) return;
  state.loading = true;
  redraw();
  try {
    const page = await call("sms_messages", { id: device, thread, before: 0, limit: 50 });
    state.messages = mergeMessages(page.items, state.messages);
    state.more = page.more;
    state.failed = "";
    settle(p, thread);
  } catch (e) {
    state.failed = problemText(String(e));
  }
  state.loading = false;
  redraw();
}

async function olderMessages(device, thread) {
  const state = phone(device).threads.get(thread);
  const oldest = state && state.messages[state.messages.length - 1];
  if (!state || !state.more || state.loading || !oldest) return;
  state.loading = true;
  try {
    const page = await call("sms_messages", { id: device, thread, before: oldest.date, limit: 50 });
    const before = state.messages.length;
    state.messages = mergeMessages(page.items, state.messages);
    state.more = page.more && state.messages.length > before;
  } catch { /* the next scroll tries again */ }
  state.loading = false;
  redraw();
}

function send(device, conversation, text) {
  const p = phone(device);
  const item = { id: Math.random().toString(36).slice(2), thread: conversation.thread, text, date: Date.now(), state: "sending" };
  p.pending = [...p.pending, item];
  redraw();
  call("sms_send", { id: device, to: conversation.addresses, text })
    .then(() => { item.state = "sent"; })
    .catch((e) => { item.state = "failed"; item.reason = problemText(String(e)); })
    .finally(() => { p.pending = [...p.pending]; redraw(); });
}

function picture(device, part) {
  const p = phone(device);
  if (p.pictures.has(part)) return;
  p.pictures.set(part, null);
  call("sms_picture", { id: device, part }).then((uri) => { if (uri) { p.pictures.set(part, uri); redraw(); } }).catch(() => {});
}

function problemText(code) {
  switch (code) {
    case "off": return t("sms_err_off");
    case "no_permission": return t("sms_err_permission");
    case "offline": return t("sms_err_offline");
    default: return code;
  }
}

// ---- Words --------------------------------------------------------------------------

function title(c) {
  if (c.name) return c.name;
  return c.addresses.length ? c.addresses.join(", ") : t("sms_unknown");
}

const time = (date) => new Date(date).toLocaleTimeString(language(), { hour: "numeric", minute: "2-digit" });

function daysAgo(date) {
  const day = (d) => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x.getTime(); };
  return Math.round((day(Date.now()) - day(date)) / 86400000);
}

/** The time in the list: the hour today, then yesterday, the day of the week, and a date. */
function listTime(date) {
  const days = daysAgo(date);
  if (days === 0) return time(date);
  if (days === 1) return t("sms_yesterday");
  if (days < 7) return new Date(date).toLocaleDateString(language(), { weekday: "long" });
  return new Date(date).toLocaleDateString(language());
}

/** Above the first message of a day or after a silence: the day, in bold, and the time. */
function dayLine(date) {
  const days = daysAgo(date);
  const day = days === 0 ? t("sms_today_word")
    : days === 1 ? t("sms_yesterday")
      : days < 7 ? new Date(date).toLocaleDateString(language(), { weekday: "long" })
        : new Date(date).toLocaleDateString(language(), { day: "numeric", month: "short", year: "numeric" });
  return html`<b>${day}</b> ${time(date)}`;
}

/** The number to call for a conversation with one other person. A short code is a number too; a sender with letters, such as a
 * bank's name, cannot be called, and neither can a group. */
function dialable(c) {
  if (c.addresses.length !== 1) return null;
  const address = c.addresses[0].trim();
  return /^[+0-9 ().*#-]+$/.test(address) && address.replace(/\D/g, "").length >= 3 ? address : null;
}

/** The letters on a face: the first of the first two words of a name (of the first two people in a group), or of a sender that is
 * a word, such as a bank. Empty for a plain number. */
function initials(name, address = "") {
  const letter = (word) => (/\p{L}/u.test(word[0] || "") ? word[0].toUpperCase() : "");
  const people = (name || "").split(", ").filter(Boolean);
  if (people.length > 1) return people.slice(0, 2).map(letter).join("");
  const source = people[0] || (/\p{L}/u.test(address) ? address : "");
  return source.split(" ").map(letter).filter(Boolean).slice(0, 2).join("");
}

// From light to deep, the same eight in the same order as `MessagesColor` on the Mac, so a person has one colour on every computer.
const AVATAR_COLORS = [
  ["#8a8af0", "#5b5bd6"], ["#f59bbe", "#d9628f"], ["#f8b26e", "#e07b35"], ["#62cec4", "#2c9f96"],
  ["#86d27f", "#4ca646"], ["#72b6f7", "#3a86dc"], ["#bc93f2", "#8b5cd6"], ["#f39a86", "#d95f49"],
];

/** FNV-1a over the code points of the name, the same sum the Mac makes. */
function colorOf(key) {
  let hash = 2166136261;
  for (const ch of key) {
    hash ^= ch.codePointAt(0);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return AVATAR_COLORS[hash % AVATAR_COLORS.length];
}

function Avatar({ name, address = "", group = false, size = 40 }) {
  const letters = initials(name, address);
  const [top, bottom] = letters ? colorOf(name || address) : ["#b3b3b8", "#8f8f96"];
  return html`<span class="sms-avatar" aria-hidden="true"
    style=${`width:${size}px;height:${size}px;font-size:${Math.round(size * (letters.length > 1 ? 0.38 : 0.44))}px;background:linear-gradient(${top}, ${bottom})`}>
    ${letters || html`<${Icon} name=${group ? "users" : "user"} size=${Math.round(size * 0.5)} stroke=${2} />`}
  </span>`;
}

const ConversationAvatar = ({ c, size }) => html`<${Avatar} name=${c.name} address=${c.addresses[0] || ""} group=${c.addresses.length > 1} size=${size} />`;

/** Has the phone place a call. The talking happens on the phone or its headset: call audio does not reach a computer. */
function dial(device, number) {
  // What stands in the way is said when someone asks for a call, not shown on the page all the time.
  const calls = (device.features || []).find((f) => f.id === "calls");
  if (calls && !calls.ready) { say(calls.why); return; }
  if (!device.online) { say(t("features_unreachable", device.name)); return; }
  call("dial", { id: device.id, number })
    .then(() => say(t("sms_calling", device.name)))
    .catch(() => say(t("features_unreachable", device.name)));
}

// ---- The page -------------------------------------------------------------------------

export function MessagesPage({ device }) {
  const [, bump] = useState(0);
  const [selection, setSelection] = useState(null);
  const [composing, setComposing] = useState(false);
  const [search, setSearch] = useState("");
  const p = phone(device.id);

  useEffect(() => {
    follow();
    const r = () => bump((n) => n + 1);
    redraws.add(r);
    return () => { redraws.delete(r); p.open = null; };
  }, [device.id]);

  useEffect(() => {
    if (device.online) refresh(device.id);
    else if (p.load !== "ready") { p.load = "offline"; redraw(); }
  }, [device.id, device.online]);

  // A conversation picked in the search of the desktop.
  const wanted = store.smsWanted;
  useEffect(() => {
    if (!wanted || wanted.device !== device.id) return;
    // Taken once, so coming back to the page later starts as it always does.
    store.smsWanted = null;
    setComposing(false);
    setSelection(wanted.thread);
  }, [wanted && wanted.at, device.id]);

  // There is room for the newest conversation from the start, as in Messages.
  const first = p.conversations[0];
  useEffect(() => {
    if (selection == null && !composing && first) setSelection(first.thread);
  }, [first && first.thread]);

  useEffect(() => {
    p.open = composing ? null : selection;
    if (selection != null && !composing) {
      markSeen(device.id, p.conversations.find((c) => c.thread === selection));
      loadThread(device.id, selection);
    }
  }, [selection, composing]);

  const back = () => set({ page: "device", selected: device.id });
  const compose = () => { setComposing(true); setSelection(null); };

  if (!p.conversations.length && p.load !== "ready" && !composing) return html`<${Notice} device=${device} p=${p} back=${back} />`;

  const query = search.trim().toLowerCase();
  const shown = query
    ? p.conversations.filter((c) => c.name.toLowerCase().includes(query) || c.snippet.toLowerCase().includes(query) || c.addresses.some((a) => a.includes(query)))
    : p.conversations;
  const open = !composing && selection != null ? p.conversations.find((c) => c.thread === selection) : null;

  return html`<div class="sms">
    <aside class="sms-list">
      <div class="sms-head">
        <button class="ib" title=${t("back")} onClick=${back}><${Icon} name="chevron-left" size=${18} /></button>
        <label class="sms-search"><${Icon} name="search" size=${15} />
          <input type="text" placeholder=${t("search")} value=${search} onInput=${(e) => setSearch(e.target.value)} />
          ${search && html`<button class="ib sms-clear" title=${t("remove")} onClick=${() => setSearch("")}><${Icon} name="x" size=${13} /></button>`}
        </label>
        <button class=${"ib" + (composing ? " on" : "")} title=${t("sms_new")} onClick=${compose}><${Icon} name="edit" size=${18} /></button>
      </div>
      <div class="sms-rows">
        ${shown.map((c) => {
          const unread = c.unread > 0 && c.date > seen(device.id, c.thread) && c.thread !== p.open;
          return html`<div key=${c.thread} class=${"sms-row" + (open && open.thread === c.thread ? " selected" : "") + (unread ? " unread" : "")}
            onClick=${() => { setComposing(false); setSelection(c.thread); }}>
            <span class="sms-dot"></span>
            <${ConversationAvatar} c=${c} size=${40} />
            <div class="grow">
              <div class="sms-row-top"><span class="ellipsis sms-row-name">${title(c)}</span><span class="sms-row-time">${listTime(c.date)}</span></div>
              <div class="sms-snippet">${c.fromMe ? t("sms_you", c.snippet) : c.snippet}</div>
            </div>
          </div>`;
        })}
        ${query && !shown.length && html`<div class="sms-empty-list muted">${t("sms_no_results")}</div>`}
        ${p.more && !query && html`<${Sentinel} key=${p.conversations.length} onSeen=${() => moreConversations(device.id)} />`}
      </div>
    </aside>
    <section class="sms-chat">
      ${composing
        ? html`<${NewMessage} device=${device} onDone=${(thread) => { setComposing(false); if (thread != null) setSelection(thread); }} />`
        : open
          ? html`<${Conversation} key=${open.thread} device=${device} conversation=${open} />`
          : html`<div class="sms-hero">
              <span class="sms-notice-mark"><${Icon} name="message" size=${28} /></span>
              <h2>${p.conversations.length ? t("sms_choose") : t("sms_none")}</h2>
              <p class="muted">${t("sms_pick_hint")}</p>
              <button class="btn accent" onClick=${compose}><${Icon} name="edit" size=${16} />${t("sms_new")}</button>
            </div>`}
    </section>
  </div>`;
}

/** Calls `onSeen` when it scrolls into view: where the next page is asked for. */
function Sentinel({ onSeen }) {
  const ref = useRef(null);
  useEffect(() => {
    const seenNow = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) onSeen(); });
    if (ref.current) seenNow.observe(ref.current);
    return () => seenNow.disconnect();
  }, []);
  return html`<div ref=${ref} class="sms-more"><span class="sms-spinner"></span></div>`;
}

/** The lines of a conversation, oldest first, with a line with the day above each new day or after an hour of silence. Messages of
 * one side that follow each other within ten minutes form a run: they stand close together and only the corners between them are
 * small, as Messages draws it. */
function lines(state, pending, group) {
  const dated = [
    ...state.messages.map((m) => ({ date: m.date, mine: m.status !== "received", from: m.status === "received" ? m.address : "", message: m })),
    ...pending.map((x) => ({ date: x.date, mine: true, from: "", pending: x })),
  ].sort((a, b) => a.date - b.date);
  const out = [];
  let previous = null;
  dated.forEach((item) => {
    if (!previous || new Date(previous.date).toDateString() !== new Date(item.date).toDateString() || item.date - previous.date > 3600000) {
      out.push({ day: item.date });
      previous = null;
    }
    item.joined = !!previous && previous.mine === item.mine && previous.from === item.from && item.date - previous.date < 600000;
    if (item.joined) previous.continues = true;
    item.named = group && !item.mine && !item.joined;
    out.push(item);
    previous = item;
  });
  return out;
}

function Conversation({ device, conversation }) {
  const p = phone(device.id);
  const state = p.threads.get(conversation.thread) || { messages: [], more: false, loading: true, failed: "" };
  const [draft, setDraft] = useState("");
  const [groupNote, setGroupNote] = useState(false);
  const group = conversation.addresses.length > 1;
  const number = dialable(conversation);

  // Drawn from the bottom up, so the list is turned around.
  const items = lines(state, p.pending.filter((x) => x.thread === conversation.thread), group).reverse();

  const submit = () => {
    const text = draft.trim();
    if (!text) return;
    if (group) { setGroupNote(true); return; }
    setDraft("");
    send(device.id, conversation, text);
  };

  const sender = (address) => {
    const names = conversation.name.split(", ");
    const at = conversation.addresses.indexOf(address);
    return names.length === conversation.addresses.length && at >= 0 ? names[at] : address;
  };

  const shape = (item) => " " + [item.joined && "joined", item.continues && "continues"].filter(Boolean).join(" ");

  return html`
    <header class="sms-chat-head">
      <${ConversationAvatar} c=${conversation} size=${32} />
      <div class="grow"><div class="sms-chat-name ellipsis">${title(conversation)}</div>
        ${conversation.name && html`<div class="small muted ellipsis">${conversation.addresses.join(", ")}</div>`}</div>
      ${number && html`<button class="ib solid" title=${t("sms_call", title(conversation))} onClick=${() => dial(device, number)}><${Icon} name="phone" size=${17} /></button>`}
    </header>
    <div class="sms-scroll">
      ${items.map((item) => item.day != null
        ? html`<div key=${"d" + item.day} class="sms-day">${dayLine(item.day)}</div>`
        : item.pending
          ? html`<${PendingBubble} key=${item.pending.id} item=${item.pending} device=${device} conversation=${conversation} shape=${shape(item)} />`
          : html`<${Bubble} key=${key(item.message)} message=${item.message} device=${device} shape=${shape(item)}
              sender=${item.named ? sender(item.message.address) : ""} />`)}
      ${!items.length && html`<div class="sms-hero">
        ${state.loading
          ? html`<span class="sms-spinner big"></span>`
          : html`<${ConversationAvatar} c=${conversation} size=${64} />
            <h2>${title(conversation)}</h2>
            <p class="muted">${state.failed || t("sms_empty_thread")}</p>`}
      </div>`}
      ${state.more && state.messages.length > 0 && html`<${Sentinel} key=${state.messages.length} onSeen=${() => olderMessages(device.id, conversation.thread)} />`}
    </div>
    ${groupNote && html`<div class="sms-note small muted">${t("sms_group_note")}</div>`}
    <${Composer} value=${draft} onInput=${setDraft} onSend=${submit} autofocus=${true} />`;
}

function Bubble({ message, device, shape, sender }) {
  const mine = message.status !== "received";
  const p = phone(device.id);
  return html`<div class=${"sms-line" + (mine ? " mine" : "") + shape} title=${new Date(message.date).toLocaleString(language())}>
    ${sender && html`<div class="sms-sender">${sender}</div>`}
    ${message.pictures.map((part) => {
      picture(device.id, part);
      const uri = p.pictures.get(part);
      return uri
        ? html`<img key=${part} class="sms-picture" src=${uri} alt=${t("sms_picture")} />`
        : html`<div key=${part} class="sms-picture empty"><${Icon} name="photo" size=${24} /></div>`;
    })}
    ${(message.body || !message.pictures.length) && html`<div class=${"sms-bubble" + (message.body ? "" : " blank")}>${message.body || t("sms_no_text")}</div>`}
    ${message.status === "failed" && html`<div class="sms-status bad"><${Icon} name="alert-triangle" size=${12} />${t("sms_not_sent")}</div>`}
  </div>`;
}

function PendingBubble({ item, device, conversation, shape }) {
  const p = phone(device.id);
  const retry = () => { p.pending = p.pending.filter((x) => x !== item); send(device.id, conversation, item.text); };
  const remove = () => { p.pending = p.pending.filter((x) => x !== item); redraw(); };
  return html`<div class=${"sms-line mine" + shape + (item.state === "sending" ? " waiting" : "")}>
    <div class="sms-bubble">${item.text}</div>
    ${item.state === "sending" && html`<div class="sms-status">${t("sms_sending")}</div>`}
    ${item.state === "failed" && html`<div class="sms-status bad"><${Icon} name="alert-triangle" size=${12} />${item.reason || t("sms_not_sent")}
      <button class="btn small" onClick=${retry}>${t("try_again")}</button><button class="btn small ghost" onClick=${remove}>${t("remove")}</button></div>`}
  </div>`;
}

/** The field at the bottom with the send button inside it: Enter sends, Shift+Enter starts a new line. */
function Composer({ value, onInput, onSend, disabled = false, autofocus = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const area = ref.current;
    if (!area) return;
    area.style.height = "auto";
    area.style.height = Math.min(area.scrollHeight, 140) + "px";
  }, [value]);
  // Opening a conversation is wanting to answer it.
  useEffect(() => { if (autofocus && ref.current) ref.current.focus(); }, []);
  const empty = !value.trim();
  return html`<footer class="sms-compose">
    <div class="sms-field">
      <textarea ref=${ref} rows="1" placeholder=${t("sms_placeholder")} value=${value} disabled=${disabled}
        onInput=${(e) => onInput(e.target.value)}
        onKeyDown=${(e) => { if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); onSend(); } }}></textarea>
      <button class="sms-send" title=${t("sms_send")} disabled=${empty || disabled} onClick=${onSend}><${Icon} name="arrow-up" size=${16} stroke=${2.4} /></button>
    </div>
  </footer>`;
}

function NewMessage({ device, onDone }) {
  const [query, setQuery] = useState("");
  const [recipient, setRecipient] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState("");
  const field = useRef(null);

  // Whoever presses New message wants to type the name next.
  useEffect(() => { if (!recipient && field.current) field.current.focus(); }, [recipient]);

  useEffect(() => {
    if (recipient || !query.trim()) { setSuggestions([]); return undefined; }
    // Typing fast asks the phone once, for what was typed last.
    const timer = setTimeout(() => call("sms_contacts", { id: device.id, query }).then(setSuggestions).catch(() => setSuggestions([])), 250);
    return () => clearTimeout(timer);
  }, [query, recipient]);

  const typed = /^[+0-9 ()-]+$/.test(query.trim()) && query.replace(/\D/g, "").length >= 3 ? query.trim() : null;
  const number = recipient ? recipient.number : typed;

  const submit = async () => {
    const text = draft.trim();
    if (!text) return;
    if (!number) { setProblem(t("sms_pick_someone")); return; }
    setProblem("");
    setSending(true);
    try {
      const thread = await call("sms_send", { id: device.id, to: [number], text });
      setDraft("");
      await refresh(device.id);
      // Android writes the message down once it is out, which can take a moment: until then it stands here as written.
      const p = phone(device.id);
      if (thread != null && !p.conversations.some((c) => c.thread === thread)) {
        const date = Date.now();
        p.conversations = mergeConversations([{ thread, addresses: [number], name: recipient ? recipient.name : "", snippet: text, date, unread: 0, fromMe: true }], p.conversations);
        p.pending = [...p.pending, { id: Math.random().toString(36).slice(2), thread, text, date, state: "sent" }];
      }
      onDone(thread);
    } catch (e) {
      setProblem(problemText(String(e)));
    }
    setSending(false);
  };

  return html`
    <header class="sms-chat-head sms-to">
      <span class="muted">${t("sms_to")}</span>
      ${recipient
        ? html`<span class="chip accent">${recipient.name || recipient.number}<button class="ib sms-clear" title=${t("sms_someone_else")} onClick=${() => setRecipient(null)}><${Icon} name="x" size=${12} /></button></span>`
        : html`<input ref=${field} class="grow" type="text" placeholder=${t("sms_name_or_number")} value=${query} onInput=${(e) => setQuery(e.target.value)} />`}
    </header>
    <div class="sms-suggestions">
      ${!recipient && suggestions.map((c) => html`<div key=${c.number} class="sms-row" onClick=${() => setRecipient(c)}>
        <${Avatar} name=${c.name} address=${c.number} size=${32} />
        <div class="grow"><div class="sms-row-name">${c.name}</div><div class="small muted">${[c.label, c.number].filter(Boolean).join(" ")}</div></div>
      </div>`)}
    </div>
    ${problem && html`<div class="sms-note small bad-text">${problem}</div>`}
    <${Composer} value=${draft} onInput=${setDraft} onSend=${submit} disabled=${sending} />`;
}

/** What stands in the way, said only once the person opened Messages, with the one thing that helps. */
function Notice({ device, p, back }) {
  const [asked, setAsked] = useState(false);
  const ask = () => call("sms_ask_access", { id: device.id })
    .then(() => { setAsked(true); say(t("sms_look", device.name)); })
    .catch(() => say(t("features_unreachable", device.name)));
  const kinds = {
    off: [t("sms_off_title"), t("sms_off_body", device.name)],
    no_permission: [t("sms_permission_title", device.name), t("sms_permission_body", device.name)],
    unavailable: [t("sms_unavailable_title", device.name), t("sms_unavailable_body")],
    offline: [t("sms_offline_title", device.name), t("sms_offline_body")],
    failed: [t("sms_failed_title"), p.problem],
  };
  const words = kinds[p.load];
  return html`<div class="sms sms-alone">
    <div class="sms-head"><button class="ib" title=${t("back")} onClick=${back}><${Icon} name="chevron-left" size=${18} /></button></div>
    <div class="sms-notice">
      ${!words
        ? html`<span class="sms-spinner big"></span>`
        : html`<span class="sms-notice-mark"><${Icon} name=${p.load === "failed" ? "alert-triangle" : "message"} size=${28} /></span>
          <h2>${words[0]}</h2><p class="muted">${words[1]}</p>
          <div class="sms-notice-actions">
            ${(p.load === "off" || p.load === "no_permission") && html`<button class="btn accent" onClick=${ask}><${Icon} name="device-mobile" size=${17} />${asked ? t("sms_ask_again") : t("sms_ask", device.name)}</button>`}
            ${(p.load === "failed" || p.load === "off" || asked) && html`<button class="btn" onClick=${() => refresh(device.id)}>${t("try_again")}</button>`}
          </div>`}
    </div>
  </div>`;
}
