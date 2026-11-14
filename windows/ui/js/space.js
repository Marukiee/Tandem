// Sending plain files to a device that has less free space than they take (P5): the person is asked first, in a small window of its own
// over the page, so it works in the main window and in the panel alike.
import { html, render } from "../vendor/preact-htm.js";
import { call } from "./backend.js";
import { t } from "./i18n.js";

/** Asks whether to send anyway. Resolves true for Send anyway, false for Cancel, Escape or a click beside it. */
export function askSpace(short) {
  return new Promise((resolve) => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const finish = (answer) => {
      window.removeEventListener("keydown", onKey, true);
      render(null, host);
      host.remove();
      resolve(answer);
    };
    const onKey = (e) => { if (e.key === "Escape") { e.stopPropagation(); finish(false); } };
    window.addEventListener("keydown", onKey, true);
    const names = short.map((d) => d.name).join(", ");
    render(html`<div class="scrim" onClick=${(e) => e.target === e.currentTarget && finish(false)}>
      <div class="dialog" role="alertdialog"><h2>${t("space_title", names)}</h2>
        <p class="muted" style="margin-top:0">${t("space_text")}</p>
        <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:14px;flex-wrap:wrap">
          <button class="btn" onClick=${() => finish(false)}>${t("space_cancel")}</button>
          <button class="btn accent" onClick=${() => finish(true)}>${t("space_anyway")}</button>
        </div></div></div>`, host);
  });
}

/**
 * Sends picked or dropped files with `send_paths` or `pick_and_send`. A device that reports less room than they take is asked about
 * first: Send anyway sends them, Cancel gives null. Not for a drag out of the window or the clipboard, which never ask.
 */
export async function sendChecked(command, args) {
  const result = await call(command, args);
  if (!result || !result.short) return result;
  if (!(await askSpace(result.short))) return null;
  return call("send_paths", { ids: args.ids, paths: result.paths || args.paths, force: true });
}
