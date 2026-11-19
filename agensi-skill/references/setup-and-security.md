# NEXUS Local Bridge — setup and security

## Intended connection

The Android app owns inference and keeps the GGUF file private. A user-started Android foreground service listens on device loopback only at 127.0.0.1:8766. The desktop agent creates an ADB forward:

    adb -s DEVICE_SERIAL forward --no-rebind tcp:8766 tcp:8766

The desktop client then calls its own 127.0.0.1:8766, which ADB transports to the phone's loopback listener. This direction is intentionally different from NEXUS CONTROL's existing adb reverse, which lets the phone browser reach the PC dashboard.

## Proposed HTTP contract

All routes require Authorization: Bearer <random-token>.

- GET /v1/status: returns {"ok":true,"bridge":"running","model_loaded":true} plus non-sensitive model metadata.
- POST /v1/generate: JSON {"prompt":"...","max_tokens":128}; returns {"ok":true,"text":"...","generation_ms":123}.

The Android implementation must enforce loopback binding, a high-entropy revocable token, request and prompt limits, a single active generation, bounded response size, no prompt/token logging, and an explicit user-controlled foreground-service lifecycle. Model loading/unloading remains in the NEXUS UI.

## Current implementation status

This repository branch currently contains only the skill/client scaffold and the existing PC dashboard. The Android HTTP service, token UI, service start/stop controls, and end-to-end device tests are not implemented yet. The public skill package must not be listed on Agensi until these are implemented and verified against this exact contract.

## User privacy

The client uses Python's standard library. It does not upload prompts to a cloud service. It never sends a prompt unless the user invokes the generate command. Store NEXUS_BRIDGE_TOKEN in the agent host's secret environment facility; do not put it in shell history, source control, skill metadata, or chat logs.
