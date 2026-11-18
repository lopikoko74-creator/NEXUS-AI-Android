#!/usr/bin/env bash
set -u
command -v adb >/dev/null || exit 0
adb start-server >/dev/null 2>&1 || true
was_connected=0
while true; do
  connected=0
  if adb devices 2>/dev/null | awk '$2=="device"{found=1} END{exit !found}'; then connected=1; fi
  if [ "$connected" -eq 1 ]; then
    adb reverse tcp:8765 tcp:8765 >/dev/null 2>&1 || true
    if [ "$was_connected" -eq 0 ] && [ -f "$HOME/.nexus-control-token" ]; then
      TOKEN="$(cat "$HOME/.nexus-control-token")"
      adb shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:8765/#token=$TOKEN" >/dev/null 2>&1 || true
    fi
  fi
  was_connected="$connected"
  sleep 5
done
