#!/usr/bin/env bash
# Test de démarrage : installe l'APK sur l'émulateur avec les permissions
# accordées, lance l'application et vérifie qu'elle tourne encore 20 s plus
# tard. En cas de plantage, la pile d'erreur part dans le résumé de la CI.
set -u
APK=androidApp/build/outputs/apk/debug/androidApp-universal-debug.apk
PAQUET=com.example.relay
RESUME=${GITHUB_STEP_SUMMARY:-/dev/stdout}

if ! adb install -r -g "$APK"; then
  echo "## Installation impossible sur l'émulateur" >> "$RESUME"
  exit 1
fi
adb logcat -c
adb shell am start -W -n "$PAQUET/com.relay.app.MainActivity"
sleep 20
adb logcat -d > logcat.txt

if grep -q "FATAL EXCEPTION" logcat.txt; then
  {
    echo "## Plantage au démarrage"
    echo '```'
    grep -A 45 "FATAL EXCEPTION" logcat.txt | head -90
    echo '```'
  } >> "$RESUME"
  grep -A 45 "FATAL EXCEPTION" logcat.txt | head -90
  exit 1
fi
if ! adb shell pidof "$PAQUET" > /dev/null; then
  echo "## L'application ne tourne plus après 20 secondes" >> "$RESUME"
  exit 1
fi
echo "## Démarrage réussi : l'application tourne après 20 secondes, permissions accordées" >> "$RESUME"
