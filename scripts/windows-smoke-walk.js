// Goes through every page of a window of the Windows and Linux app in both languages and says what is wrong with what a person reads
// there. A compiler and a unit test look at none of this: words of another system, a key without a translation, text that falls off
// the window. Evaluated in the page, by scripts/windows-smoke.ps1 in the real app and against the pretend data in a browser.
(async () => {
  const load = (name) => import(new URL("js/" + name, location.href).href);
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  // What a person on this system has nothing to do with. "Mac" itself is not here: a Mac can be one of the devices, and then a sentence
  // about what to do on that Mac is right. Those are listed apart, to be read by a person.
  const otherSystem = /\b(macOS|OS X|Finder|Touch ID|Spotlight|AirDrop|menu bar|menubalk|Keychain|Sleutelhanger|Control Center|Bedieningspaneel|Launchpad|iCloud|Cmd)\b|⌘|⌥/i;
  const otherDesktop = /\b(GNOME|KDE|Wayland|X11|GVfs|Nautilus|Dolphin|systemd|BlueZ|PipeWire|MPRIS|logind)\b/;
  // How a slip in the words shows: a key that has no text, an empty place in a sentence, a value that never came, a plural made by a sum.
  const broken = /\b[a-z]+(_[a-z0-9]+)+\b|\{\d\}|\bundefined\b|\[object Object\]|\bNaN\b|\b1 (things|files|devices|dingen|bestanden|apparaten)\b/;

  const report = { steps: [], findings: [], macWords: [] };

  function look(lang, where, names) {
    let text = (document.body.innerText || "").replace(/\s*\n\s*/g, "\n").trim();
    // The name of a device or a file is somebody's own word, not one of ours.
    for (const name of names) text = text.split(name).join("");
    report.steps.push({ lang, where, text });
    const add = (what, excerpt) => report.findings.push({ lang, where, what, excerpt: String(excerpt).slice(0, 160) });
    for (const line of text.split("\n")) {
      if (otherSystem.test(line)) add("a word of another system", line);
      if (platform === "windows" && otherDesktop.test(line)) add("a word of a Linux desktop", line);
      if (broken.test(line)) add("a slip in the words", line);
      if (/\b(Mac|MacBook)\b/.test(line)) report.macWords.push({ lang, where, line: line.slice(0, 160) });
    }
    const width = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth > width + 1) add("the page is wider than the window", `${document.documentElement.scrollWidth} > ${width}`);
    for (const element of document.querySelectorAll("body *")) {
      const box = element.getBoundingClientRect();
      if (!box.width || !box.height) continue;
      // In a list that scrolls sideways or a clipped parent going past the edge is the design; only what can really not be reached counts.
      if (box.right > width + 1 && !element.closest("[style*='overflow'], .scroll, pre")) {
        add("something falls off the window", `${element.tagName.toLowerCase()}.${element.className}: ${(element.innerText || "").slice(0, 60)}`);
        break;
      }
    }
    for (const button of document.querySelectorAll("button")) {
      const named = (button.innerText || "").trim() || button.title || button.getAttribute("aria-label");
      if (!named && button.getBoundingClientRect().width) add("a button without a name or hover label", button.outerHTML);
    }
  }

  // A small window of its own: there is nothing to walk through, only what it shows now.
  const isMain = /(^|\/)(index\.html)?$/.test(location.pathname);
  const i18n = await load("i18n.js");
  let platform = document.documentElement.dataset.platform || "windows";
  if (!isMain) {
    look(i18n.language(), location.pathname.split("/").pop(), []);
    return report;
  }

  const [store, backend] = await Promise.all([load("store.js"), load("backend.js")]);
  const { state, set } = store;
  platform = state.platform || platform;
  const before = { page: state.page, selected: state.selected, dialog: state.dialog, language: state.settings.language };
  const useLanguage = async (language) => {
    set({ settings: await backend.call("set_settings", { patch: { language } }) });
    i18n.setLanguage(language, state.systemLanguage);
    set({});
    await wait(300);
  };
  const go = async (patch, ms = 500) => {
    set(patch);
    await wait(ms);
  };

  for (const lang of ["en", "nl"]) {
    await useLanguage(lang);
    const names = () => state.devices.map((d) => d.name).concat(state.self && state.self.name ? [state.self.name] : []);
    for (const page of ["tour", "shared", "clipboard", "notifications"]) {
      await go({ page });
      look(lang, page, names());
    }
    await go({ page: "settings" });
    const sections = document.querySelectorAll(".settings-nav button").length;
    for (let i = 0; i < sections; i++) {
      const section = document.querySelectorAll(".settings-nav button")[i];
      section.click();
      await wait(400);
      look(lang, "settings: " + section.innerText.trim(), names());
    }
    for (const device of state.devices) {
      await go({ page: "device", selected: device.id });
      const isMac = device.platform === "macos" || device.kind === "mac";
      look(lang, "device " + (isMac ? "(a Mac)" : device.kind || device.platform || "") + ": " + device.name, names());
      if (device.kind === "phone" || device.platform === "android") {
        await go({ page: "files", selected: device.id });
        look(lang, "files of " + device.name, names());
        await go({ page: "messages", selected: device.id });
        look(lang, "messages of " + device.name, names());
      }
    }
    await go({ page: "device", dialog: { kind: "pair" } }, 800);
    look(lang, "add a device", names());
    await go({ dialog: null }, 100);
    await backend.call("cancel_pairing").catch(() => {});
  }

  await useLanguage(before.language);
  await go({ page: before.page, selected: before.selected, dialog: before.dialog }, 100);
  return report;
})()
