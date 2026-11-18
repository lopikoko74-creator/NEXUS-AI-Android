#!/usr/bin/env bash
set -u
command -v adb >/dev/null || exit 0
adb start-server >/dev/null 2>&1 || true
while true; do
  if adb devices 2>/dev/null | awk '$2=="device"{found=1} END{exit !found}'; then
    adb reverse tcp:8765 tcp:8765 >/dev/null 2>&1 || true
  fi
  sleep 5
done
