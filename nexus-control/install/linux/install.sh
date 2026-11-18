#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$HOME/.local/share/nexus-control"
mkdir -p "$DEST"
cp -r "$ROOT/agent" "$DEST/"
python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install --upgrade pip
"$DEST/venv/bin/pip" install -r "$DEST/agent/requirements.txt"
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/nexus-control.service" <<EOF
[Unit]
Description=NEXUS CONTROL PC Agent
After=default.target

[Service]
Type=simple
WorkingDirectory=$DEST/agent
ExecStart=$DEST/venv/bin/python $DEST/agent/nexus_agent.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now nexus-control.service
echo "Installed. Agent listens only on 127.0.0.1:8765."
echo "Token: cat ~/.nexus-control-token"
echo "USB: enable Android USB debugging, authorize this PC, then run: adb reverse tcp:8765 tcp:8765"
