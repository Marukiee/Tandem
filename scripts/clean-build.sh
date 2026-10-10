#!/bin/bash
# Geeft schijfruimte terug. Cargo ruimt oude bouwresultaten nooit zelf op: een debug-map van een paar maanden werk is gauw 30 GB,
# en elke werkmap van een agent heeft er zelf een. Hierna bouwt alles gewoon opnieuw (een paar minuten).
#
#   scripts/clean-build.sh          ruimt op
#   scripts/clean-build.sh --check  laat alleen zien wat het zou doen
set -euo pipefail
cd "$(dirname "$0")/.."
check=false
[ "${1:-}" = "--check" ] && check=true
size() { du -sh "$1" 2>/dev/null | cut -f1; }

# Werkmappen van agents die klaar zijn: niets dat niet in main zit en niets dat nog niet gecommit is. De rest blijft staan.
if [ -d .claude/worktrees ]; then
  for w in .claude/worktrees/*/; do
    [ -d "$w" ] || continue
    branch=$(git -C "$w" branch --show-current 2>/dev/null || true)
    ahead=$(git rev-list --count "main..$branch" 2>/dev/null || echo 1)
    dirty=$(git -C "$w" status --short 2>/dev/null | grep -v '^??' | wc -l | tr -d ' ')
    loose=$(git -C "$w" ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')
    if [ "$ahead" = "0" ] && [ "$dirty" = "0" ] && [ "$loose" = "0" ]; then
      echo "werkmap $(basename "$w"): $(size "$w"), klaar"
      $check || { git worktree remove --force "$w"; git branch -d "$branch" >/dev/null 2>&1 || true; }
    else
      echo "werkmap $(basename "$w"): blijft staan (nog $ahead commits niet in main, $dirty wijzigingen, $loose losse bestanden)"
    fi
  done
  $check || git worktree prune
fi

# De debug-mappen van de twee Rust-werkruimtes en wat Swift en Gradle erbij hebben liggen.
for dir in target/debug windows/src-tauri/target/debug target/aarch64-apple-darwin macos/.build; do
  [ -d "$dir" ] || continue
  echo "$dir: $(size "$dir")"
  $check || rm -rf "$dir"
done
echo "klaar, het project is nu $(size .)"
