#!/usr/bin/env bash
# Downloads Material Symbols (Rounded, weight 400) as vector drawables.
# Usage: scripts/fetch-symbols.sh   (edit the lists below to add icons)
set -euo pipefail
cd "$(dirname "$0")/.."
DEST="android/app/src/main/res/drawable"
BASE="https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android"
mkdir -p "$DEST"

OUTLINE=(
  add arrow_back arrow_upward backspace battery_full bluetooth bolt call call_end check check_circle
  chevron_right close computer content_copy content_paste dark_mode delete description desktop_windows
  devices do_not_disturb_on download error folder_open hub image info key keyboard keyboard_arrow_down
  keyboard_arrow_left keyboard_arrow_right keyboard_arrow_up keyboard_return laptop_mac lan language
  link mail more_vert mouse notifications notifications_active open_in_new palette pause
  photo_camera play_arrow qr_code_2 qr_code_scanner question_mark refresh screenshot search send
  settings share shield signal_cellular_alt skip_next skip_previous smartphone swap_vert sync
  system_update tab touch_app tune upload vibration volume_down volume_off volume_up wifi
  wifi_tethering lock_open history restart_alt person tablet_android watch tv desktop_mac bedtime power_settings_new music_note
  flash_on flash_off flash_auto cameraswitch photo_library document_scanner
)
FILLED=(devices swap_vert tune check_circle notifications_active bolt)
# The keys of the on-screen keyboard in the remote screen.
KEYS=(arrow_downward arrow_forward keyboard_command_key keyboard_option_key keyboard_control_key shift)

fetch() { # name, remote file, local name
  local url="$BASE/$1/materialsymbolsrounded/$2"
  [ -s "$DEST/$3.xml" ] && return 0
  curl -fsS -m 30 "$url" -o "$DEST/$3.xml" || { echo "missing: $1 ($2)"; rm -f "$DEST/$3.xml"; return 0; }
  # Compose tints icons itself, and it cannot read the theme attribute the file uses.
  sed -i.bak -e 's|android:tint="[^"]*"||' -e 's|@android:color/white|#FF000000|g' "$DEST/$3.xml" && rm -f "$DEST/$3.xml.bak"
}
for name in "${OUTLINE[@]}"; do fetch "$name" "${name}_24px.xml" "sym_$name"; done
for name in "${KEYS[@]}"; do fetch "$name" "${name}_24px.xml" "sym_$name"; done
for name in "${FILLED[@]}"; do fetch "$name" "${name}_fill1_24px.xml" "sym_${name}_filled"; done
echo "symbols: $(ls "$DEST"/sym_*.xml | wc -l) drawables"
