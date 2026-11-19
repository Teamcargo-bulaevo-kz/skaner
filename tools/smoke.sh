#!/usr/bin/env bash
set -euo pipefail
PKG=kz.bulaevo.skaner
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
adb install -r "$APK"
adb shell am start -n "$PKG/.MainActivity"
sleep 7
adb exec-out screencap -p > smoke.png
adb logcat -d -s AndroidRuntime:E > crash.log
if grep -q "FATAL EXCEPTION" crash.log; then
  cat crash.log
  exit 1
fi
adb shell dumpsys activity activities | grep -q "$PKG"
echo "Skaner launch smoke test passed"
