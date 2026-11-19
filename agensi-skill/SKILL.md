---
name: nexus-local-ai
description: Use an already-installed NEXUS AI Android app as a private local GGUF inference engine over an explicitly authorized USB ADB connection. Requires the NEXUS Local Bridge feature in the Android app.
---

# NEXUS Local AI

Use this skill only when the user wants inference through their own NEXUS Android installation.

## Requirements

- NEXUS AI Android with the Local Bridge feature enabled by the user.
- A GGUF model already loaded manually in NEXUS. The skill must never load, download, or replace a model.
- Android USB debugging enabled and this computer explicitly authorized on the phone.
- Android Platform Tools (adb) and Python 3.10+ on the agent host.
- The user starts Local Bridge inside NEXUS and stores its temporary bearer token in the environment variable NEXUS_BRIDGE_TOKEN. Never ask the user to paste the token into a chat transcript.

## Setup

1. Connect the unlocked Android phone over USB.
2. Approve the Android USB debugging prompt if it appears.
3. In NEXUS, explicitly start Local Bridge and store its temporary token in the agent host's secret environment store.
4. Run: python scripts/nexus_bridge.py status
5. Continue only if the authenticated status response says the bridge is running and a model is loaded.

The helper creates a USB ADB forward from host loopback port 8766 to the phone's loopback port 8766. It refuses zero or multiple authorized devices. It never enables USB debugging or approves devices automatically.

## Operations

- Status: python scripts/nexus_bridge.py status
- Local generation: python scripts/nexus_bridge.py generate --prompt "..." --max-tokens 128
- Cancel current generation: python scripts/nexus_bridge.py cancel

Only send prompts the user has authorized. Generation is local to the phone. Do not send secrets, passwords, or unrelated personal data unless the user explicitly requests it.

## Safety and failure handling

- This skill provides inference only. It cannot tap the screen, open apps, send messages, make calls, access files, run shell commands on Android, or control the PC.
- Never attempt to bypass Android permissions, USB authorization, bridge authentication, or NEXUS confirmation prompts.
- If ADB is missing, the phone is unauthorized, more than one device is connected, the bridge is stopped, authentication fails, or no model is loaded, stop and explain the exact issue.
- Never claim that generation succeeded unless the bridge returns a successful response containing generated text.
- Never print or log the bearer token.
- The helper binds/contacts host loopback only. Do not expose its forwarded port to a LAN or public network.

## Compatibility note

The Android Local Bridge is a prerequisite. The current public NEXUS release does not yet expose the inference endpoints described by this skill. Do not install or sell this package as a working integration until the matching Android bridge release and end-to-end tests are available.
