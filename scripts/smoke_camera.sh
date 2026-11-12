#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Indica la ruta del APK de depuración}
sdk=${ANDROID_HOME:?Falta ANDROID_HOME}
state=$(mktemp -d "${RUNNER_TEMP:-/tmp}/camaratrix-smoke.XXXXXX")
export ANDROID_AVD_HOME="$state/avd"
mkdir -p "$ANDROID_AVD_HOME"
app=com.hender.fotoanimada
adb=("$sdk/platform-tools/adb" -s emulator-5554)
image="system-images;android-35;google_apis;x86_64"
emulator_pid=""

cleanup() {
  local status=$?
  if [[ -n $emulator_pid ]]; then
    if ((status != 0)); then
      tail -n 80 "$state/emulator.log"
      timeout 10s "${adb[@]}" logcat -d -t 300 || true
    fi
    timeout 10s "${adb[@]}" emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

printf 'no\n' | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd \
  --name camaratrix-smoke --package "$image" --device pixel
"$sdk/emulator/emulator" -avd camaratrix-smoke -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu software \
  -camera-back emulated -camera-front emulated > "$state/emulator.log" 2>&1 &
emulator_pid=$!

timeout 240s "${adb[@]}" wait-for-device
deadline=$((SECONDS + 180))
until [[ $("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; do
  if ((SECONDS >= deadline)); then
    echo "El emulador no terminó de iniciar" >&2
    exit 1
  fi
  sleep 2
done

"${adb[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb[@]}" shell wm dismiss-keyguard
"${adb[@]}" install -g "$apk"
"${adb[@]}" shell run-as "$app" mkdir -p shared_prefs
"${adb[@]}" shell -T "run-as $app sh -c 'cat > shared_prefs/MainActivity.xml'" <<'XML'
<map><boolean name="live_on" value="false" /></map>
XML

find_shutter() {
  for attempt in {1..5}; do
    if timeout 12s "${adb[@]}" shell uiautomator dump /sdcard/camaratrix-window.xml >/dev/null &&
       "${adb[@]}" pull /sdcard/camaratrix-window.xml "$state/window.xml" >/dev/null; then
      if python3 - "$state/window.xml" "$app:id/shutter" <<'PY'
import re
import sys
from xml.etree import ElementTree

for node in ElementTree.parse(sys.argv[1]).iter("node"):
    if node.get("resource-id") == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        if x2 > x1 and y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
      then
        return
      fi
    fi
    sleep 2
  done
  echo "No se encontró el obturador de Camaratrix" >&2
  return 1
}

previous=""
capture() {
  local hdr=$1 coordinates photo deadline
  "${adb[@]}" shell am force-stop "$app"
  "${adb[@]}" shell -T "run-as $app sh -c 'cat > shared_prefs/settings.xml'" <<XML
<map>
  <string name="hdr">$hdr</string>
  <string name="enhance">off</string>
  <string name="night_auto">off</string>
  <string name="zsl">off</string>
  <string name="photo_res">3</string>
</map>
XML
  "${adb[@]}" logcat -c
  "${adb[@]}" shell am start -W -n "$app/.MainActivity"
  coordinates=$(find_shutter)
  sleep 3
  read -r x y <<< "$coordinates"
  "${adb[@]}" shell input tap "$x" "$y"

  deadline=$((SECONDS + 45))
  while ((SECONDS < deadline)); do
    photo=$("${adb[@]}" shell 'for path in /sdcard/DCIM/Camaratrix/*.jpg; do
      if [ -s "$path" ]; then printf "%s\n" "$path"; fi
    done' | tr -d '\r' | awk -v previous="$previous" '$0 != previous && !found { print; found=1 }')
    if [[ -n $photo ]]; then
      "${adb[@]}" pull "$photo" "$state/photo-$hdr.jpg"
      python3 - "$state/photo-$hdr.jpg" <<'PY'
import pathlib
import sys

photo = pathlib.Path(sys.argv[1]).read_bytes()
if len(photo) < 1024 or not photo.startswith(b"\xff\xd8") or not photo.endswith(b"\xff\xd9"):
    sys.exit("La foto guardada no parece un JPEG completo")
PY
      previous=$photo
      echo "Se guardó un JPEG en DCIM con el ajuste HDR en $hdr"
      if [[ $hdr == on ]]; then
        if [[ $photo == *_HDR.jpg ]]; then
          echo "El emulador guardó la fusión HDR"
        else
          echo "El emulador guardó una foto sin fusión HDR"
        fi
      fi
      "${adb[@]}" logcat -d -s Camaratrix
      return
    fi
    sleep 2
  done
  echo "Camaratrix no guardó una foto con el ajuste HDR en $hdr" >&2
  return 1
}

capture off
capture on
