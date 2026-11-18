#!/usr/bin/env bash
set -euo pipefail
command -v adb >/dev/null || { echo "Install Android platform-tools (adb) first."; exit 1; }
adb devices
adb reverse tcp:8765 tcp:8765
echo "USB tunnel ready. Open http://127.0.0.1:8765 on Android."
