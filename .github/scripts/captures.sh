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
# Courte vidéo de démonstration, application ouverte une seule fois : on
# passe d'un écran à l'autre sans redémarrer (sinon écrans blancs), puis un
# vrai maintien de 2,5 s sur le bouton d'urgence (Pixel 6, 1080x2400)
aller() { adb shell am start -n "$PAQUET/com.relay.app.Demonstration" --es ecran "$1" > /dev/null; }
adb shell am force-stop "$PAQUET"
aller reseau
sleep 8
adb shell screenrecord --bit-rate 6000000 --time-limit 40 /sdcard/relay-demo.mp4 &
ENREGISTREMENT=$!
sleep 7
aller conversation; sleep 6
aller verifier;     sleep 5
aller urgence;      sleep 3
# Vrai appui maintenu (un glissement sur place n'était pas reconnu)
adb shell input motionevent DOWN 540 1305
sleep 2.8
adb shell input motionevent UP 540 1305
sleep 4
adb exec-out screencap -p > captures/relay-urgence-envoyee.png
adb shell pkill -INT screenrecord || true
wait "$ENREGISTREMENT" 2>/dev/null || true
sleep 2
adb pull /sdcard/relay-demo.mp4 captures/relay-demo.mp4 && echo "vidéo : $(du -h captures/relay-demo.mp4 | cut -f1)"

adb logcat -d > captures/logcat.txt

if grep -q "FATAL EXCEPTION" captures/logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" captures/logcat.txt | head -80
  exit 1
fi
