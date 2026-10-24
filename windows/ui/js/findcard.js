// The card that says another device is looking for this computer while it plays its sound. Stop, any key, or a click on the card ends it.
import { html, render } from "../vendor/preact-htm.js";
import { call, listen } from "./backend.js";
import { Icon } from "./icons.js";

const stop = () => call("find_stop");

function draw(card) {
  render(card && html`<div class="qs-card" onClick=${stop}>
    <div class="qs-head">
      <div class="qs-icon"><${Icon} name="bell-ringing" size=${20} /></div>
      <div><div class="qs-title">${card.title}</div><div class="qs-sub">${card.body}</div></div>
    </div>
    <div class="qs-buttons"><button class="btn accent" onClick=${stop}>${card.stop}</button></div>
  </div>`, document.getElementById("cards"));
}

document.addEventListener("keydown", stop);
draw(await call("find_card"));
listen("find-card", draw);
