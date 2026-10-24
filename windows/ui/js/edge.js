// The window at the edge of the screen that catches a drag (see src-tauri/src/edge.rs). The app hears of the drag as soon as it comes
// over the window and hands it on; a drop that comes before that is sent here.
import { call } from "./backend.js";

const tauri = window.__TAURI__;
if (tauri) {
  tauri.event.listen("tauri://drag-drop", (event) => {
    call("edge_send", { paths: (event.payload && event.payload.paths) || [] }).catch(() => {});
  });
}
