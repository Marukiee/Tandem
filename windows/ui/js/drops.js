// Files dropped on a window go where they were let go: a device in the sidebar or the tray panel, the open folder of the Files page,
// or the device whose page is open. Somewhere else they go nowhere, and the window says so rather than guessing a device.
import { call } from "./backend.js";
import { t } from "./i18n.js";
import { say, state } from "./store.js";

/**
 * The open folder of the Files page, set by that page: the device, and a way to put the paths in it. Null while no folder of another
 * device is open.
 */
export const filesFolder = { current: null };

/** The pages that belong to the device chosen in the sidebar; a drop on them is for that device. */
const DEVICE_PAGES = ["device", "files", "messages", "watch", "health"];

/**
 * Where a drop at `at` (CSS pixels in this window) would go: `{ device }`, `{ folder }`, or null. `pageDrops` is false in the tray panel,
 * which has only its rows.
 */
export function dropTarget(at, pageDrops = true) {
  const element = at ? document.elementFromPoint(at.x, at.y) : null;
  const row = element && element.closest("[data-device-id]");
  if (row) {
    const device = state.devices.find((d) => d.id === row.dataset.deviceId);
    return device ? { device, element: row } : null;
  }
  if (!pageDrops) return null;
  const page = element ? element.closest("main") : document.querySelector("main");
  if (!page) return null;
  if (state.page === "files" && filesFolder.current) return { folder: filesFolder.current, element: page };
  const device = DEVICE_PAGES.includes(state.page) && state.devices.find((d) => d.id === state.selected);
  return device ? { device, element: page, page: true } : null;
}

let marked = null;

/** Shows where a drag that hangs over the window would land, and gives that place (or null). */
export function markDropTarget(at, pageDrops = true) {
  const target = at ? dropTarget(at, pageDrops) : null;
  const element = target && target.element;
  if (element !== marked) {
    if (marked) marked.classList.remove("drop-at");
    // The page of a device has a drop zone of its own that lights up; the rows and the Files page are marked here.
    if (element && !target.page) element.classList.add("drop-at");
    marked = element && !target.page ? element : null;
  }
  return target;
}

/** Sends or puts the paths where they were let go. */
export async function dropPaths(paths, at, pageDrops = true) {
  markDropTarget(null);
  if (!paths.length) return;
  const target = dropTarget(at, pageDrops);
  if (!target) { say(t("drop_on_device")); return; }
  if (target.folder) { target.folder.put(paths); return; }
  const device = target.device;
  if (!device.online) { say(t("drop_offline", device.name)); return; }
  try {
    const result = await call("send_paths", { ids: [device.id], paths });
    if (result.folders) say(t("folders_skipped"));
    else if (result.sent) say(t("files_offered"));
    if (result.offline) say(t("offline_n", result.offline));
  } catch (e) { say(String(e && e.message ? e.message : e)); }
}
