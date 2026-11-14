// The name of a round button, shown at once. A button with only an icon says what it does in its title, and the system shows
// that after a wait of a second or so, in its own style. Here the same words come up right away, under the button.
const ROUND = ".ib, .iconbtn, .wbtn";
let tip = null;
let owner = null;

function hide() {
  if (!owner) return;
  // The title goes back, so a screen reader and the next hover still find it.
  owner.setAttribute("title", owner.dataset.tip);
  delete owner.dataset.tip;
  owner = null;
  tip.classList.remove("on");
}

function show(button) {
  const words = button.getAttribute("title");
  if (!words) return;
  if (!tip) { tip = document.createElement("div"); tip.className = "tip"; tip.setAttribute("role", "tooltip"); document.body.appendChild(tip); }
  hide();
  owner = button;
  button.dataset.tip = words;
  // Without its title the system does not show a second label over this one.
  button.removeAttribute("title");
  tip.textContent = words;
  const at = button.getBoundingClientRect();
  const size = tip.getBoundingClientRect();
  const left = Math.min(Math.max(6, at.left + at.width / 2 - size.width / 2), window.innerWidth - size.width - 6);
  const below = at.bottom + 6 + size.height < window.innerHeight;
  tip.style.left = left + "px";
  tip.style.top = (below ? at.bottom + 6 : at.top - size.height - 6) + "px";
  tip.classList.add("on");
}

export function watchTips() {
  document.addEventListener("mouseover", (e) => {
    const button = e.target.closest && e.target.closest(ROUND);
    if (button === owner) return;
    if (button) show(button); else hide();
  });
  document.addEventListener("mousedown", hide, true);
  window.addEventListener("blur", hide);
  document.addEventListener("scroll", hide, true);
}
