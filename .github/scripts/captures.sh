#!/usr/bin/env bash
# Captures d'écran de démonstration : installe l'APK de test, met la barre
# d'état en mode démonstration (heure, batterie et réseau fixes), ouvre chaque
# écran rempli d'exemples (activité Demonstration) et le photographie.
set -u
APK=androidApp/build/outputs/apk/debug/androidApp-universal-debug.apk
PAQUET=com.atagba.relay
mkdir -p captures

adb install -r -g "$APK" || { echo "Installation impossible" >&2; exit 1; }

# Barre d'état propre et identique sur toutes les captures
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 76 -e plugged false
demo network -e wifi hide -e mobile hide
demo notifications -e visible false

for ecran in reseau messages conversation fiche urgence reglages verifier; do
  adb shell am force-stop "$PAQUET"
  adb shell am start -W -n "$PAQUET/com.relay.app.Demonstration" --es ecran "$ecran" > /dev/null
  sleep 6
  adb exec-out screencap -p > "captures/relay-$ecran.png"
  echo "capture : $ecran ($(du -h "captures/relay-$ecran.png" | cut -f1))"
done
adb logcat -d > captures/logcat.txt

if grep -q "FATAL EXCEPTION" captures/logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" captures/logcat.txt | head -80
  exit 1
fi
