#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if ! command -v python3 >/dev/null || ! python3 -c 'import venv' >/dev/null 2>&1 || ! command -v adb >/dev/null; then
  echo "[1/4] Installing Python venv and Android ADB tools..."
  sudo apt-get update
  sudo apt-get install -y python3 python3-venv python3-pip adb
fi
echo "[2/4] Installing NEXUS CONTROL agent..."
bash "$ROOT/install/linux/install.sh"
echo "[3/4] Connect the TECNO by USB, unlock it and approve the USB debugging prompt."
adb start-server >/dev/null
AUTHORIZED=0
for i in $(seq 1 90); do
  if adb devices | awk '$2=="device"{found=1} END{exit !found}'; then AUTHORIZED=1; break; fi
  sleep 2
done
if [ "$AUTHORIZED" -ne 1 ]; then
  echo "Phone not authorized. Enable USB debugging, approve the RSA prompt, then run this script again."
  exit 2
fi
adb reverse tcp:8765 tcp:8765
TOKEN="$(cat "$HOME/.nexus-control-token")"
URL="http://127.0.0.1:8765/#token=$TOKEN"
QR="$HOME/.local/share/nexus-control/NEXUS-CONTROL-QR.png"
"$HOME/.local/share/nexus-control/venv/bin/python" -c 'import qrcode,sys; qrcode.make(sys.argv[1]).save(sys.argv[2])' "$URL" "$QR"
echo "[4/4] NEXUS CONTROL IS READY. Scan the QR code with your phone camera."
xdg-open "$QR" >/dev/null 2>&1 || echo "QR image: $QR"
echo "USB tunnel active. Dashboard URL: $URL"
