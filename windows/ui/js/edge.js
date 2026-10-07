// The drop zone at the edge of the screen (see src-tauri/src/edge.rs). Files let go of on it are sent to the computer that is using the
// pointer of this one.
import { call } from "./backend.js";
import { iconMarkup } from "./icons.js";
import { t } from "./i18n.js";

const zone = document.getElementById("zone");
const params = new URLSearchParams(location.search);
document.getElementById("name").textContent = params.get("name") || "";
document.getElementById("hint").textContent = t("edge_drop_here");
document.getElementById("icon").innerHTML = iconMarkup("send", 26, 1.9);

const tauri = window.__TAURI__;
if (tauri) {
  tauri.event.listen("tauri://drag-enter", () => zone.classList.add("over"));
  tauri.event.listen("tauri://drag-leave", () => zone.classList.remove("over"));
  tauri.event.listen("tauri://drag-drop", (event) => {
    zone.classList.remove("over");
    call("edge_send", { paths: (event.payload && event.payload.paths) || [] }).catch(() => {});
  });
}
