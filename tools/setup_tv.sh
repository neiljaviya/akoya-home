#!/usr/bin/env bash
# Akoya Home – one-shot TV setup. Usage:
#   ./tools/setup_tv.sh <TV-IP> [path/to/akoya-home.apk] [--replace-google-launcher]
set -e
IP="$1"; APK="${2:-app/build/outputs/apk/release/app-release.apk}"; PKG=com.akoya.home
[ -z "$IP" ] && { echo "Usage: $0 <TV-IP> [apk] [--replace-google-launcher]"; exit 1; }
adb connect "$IP:5555"
echo ">> If the TV shows 'Allow USB debugging?', choose Always allow, then press Enter"; read -r _
adb -s "$IP:5555" install -r "$APK"
adb -s "$IP:5555" shell pm grant $PKG com.android.providers.tv.permission.READ_EPG_DATA || true
adb -s "$IP:5555" shell appops set $PKG GET_USAGE_STATS allow || true
adb -s "$IP:5555" shell settings put secure screensaver_components $PKG/.dream.AkoyaDreamService || true
adb -s "$IP:5555" shell cmd package set-home-activity $PKG/.MainActivity || true
if [[ " $* " == *" --replace-google-launcher "* ]]; then
  echo ">> Disabling the Google TV launcher (undo: adb shell pm enable com.google.android.apps.tv.launcherx)"
  adb -s "$IP:5555" shell pm disable-user --user 0 com.google.android.apps.tv.launcherx || true
fi
adb -s "$IP:5555" shell am start -n $PKG/.MainActivity
echo "Done. Press Home on the remote to test."
