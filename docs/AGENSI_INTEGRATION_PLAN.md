# Agensi integration — implementation plan

Status: design checkpoint; no runtime bridge or Agensi listing is implemented yet.

## Goal

Publish a separate Agensi skill that lets a compatible desktop agent use the user's already-installed NEXUS Android app as a local inference engine. The APK remains a separate product. This integration must not require uploading GGUF models, sending prompts to a NEXUS cloud endpoint, or changing the existing app distribution model.

## Verified Agensi constraints

- Agensi lists SKILL.md packages, not APKs or Android applications.
- A package may contain text files and scripts; executable binaries such as APK/JAR/SO/DLL are rejected.
- Scripts are run by the buyer's agent from a temporary file and removed afterwards.
- Agensi delivers skills through MCP and does not host or run a creator's MCP server.
- A skill may explain how to use a separately installed local service, but must state local prerequisites clearly.
- One skill ZIP can be up to 50 MB on the website; each script should remain below the 20 KB review guideline.

## Current NEXUS findings (main branch audit)

- NEXUS uses a local GGUF model through llama.cpp/JNI.
- `NativeLlamaEngine` already exposes suspend functions for model loading, generation, cancellation and unloading.
- The model is loaded deliberately by the user; the README correctly warns that it is not automatic.
- The current model-driven agent loop is owned by `MainActivity` and uses Activity/UI state.
- The Android manifest currently has no exported local API component or local HTTP bridge.
- `NexusAccessibilityService` is registered but is not a public agent API. It deliberately does not read windows continuously.
- The separate `nexus-control/` PC dashboard exposes authenticated PC telemetry/control, not NEXUS GGUF inference. It must not be misrepresented as the Android inference bridge.

## Proposed architecture

### Phase 1 — local inference bridge (read-only)

Add an explicitly user-started Android foreground service that exposes a small HTTP API on device loopback only. The service must not start at boot and must stop when the user disables it.

Initial endpoints:
- `GET /v1/status`: app version, bridge state, model-loaded state, model metadata if available; never expose model file contents or private paths.
- `POST /v1/generate`: accept a bounded prompt and token limit, call the existing `NativeLlamaEngine.generate`, and return generated text plus timing metadata.
- `POST /v1/cancel`: cancel an active generation if the native engine supports it safely.

Security requirements:
- Bind only to `127.0.0.1`; never bind to LAN or public interfaces.
- Generate a high-entropy random bearer token locally. Show it only in the NEXUS UI and provide a revoke/regenerate action.
- Require bearer authentication on every endpoint; compare secrets without timing-sensitive early exit where practical.
- Enforce request-body, prompt-length, token-count, concurrency and timeout limits.
- Do not log prompt bodies, bearer tokens, model output or personal data.
- Keep Android foreground-service notification visible while the bridge is active.
- Do not expose arbitrary shell, file-system, intent, tap, message, call, purchase or delete operations.
- Model loading/unloading remains controlled by NEXUS UI in this first phase.

### Phase 2 — desktop-to-phone transport

For a USB-connected Android phone, the desktop helper may establish an explicit ADB forward from a desktop loopback port to the phone's loopback bridge port. The helper must:
- Check that ADB exists and that exactly one authorized device is selected.
- Never enable USB debugging or approve device authorization automatically.
- Never expose the bridge on the desktop LAN.
- Require the user to start the NEXUS bridge and approve/enter its token.
- Remove the ADB forward when the session ends.
- Avoid persisting the token; never put it in command-line arguments or logs.

Wi-Fi transport is out of scope for the first release.

### Phase 3 — Agensi skill package

The ZIP will contain only text files, for example:
- `SKILL.md`
- `scripts/nexus_bridge.py` (Python standard library only; below 20 KB)
- `references/security-and-setup.md`

The skill will check prerequisites, explain how to start the bridge in NEXUS, verify authenticated status, submit bounded prompts, and clearly report when the phone/model/bridge is unavailable. It must never claim that an action ran when the bridge did not confirm it.

## Acceptance tests

1. Android service binds to loopback only and is not exported to other apps.
2. No request works without a valid bearer token.
3. A valid status request reports model unloaded without attempting to load it.
4. Generation fails clearly when no GGUF model is loaded.
5. Generation succeeds locally when a model is loaded; airplane mode does not break it.
6. Oversized prompts, invalid JSON, excessive token limits and concurrent requests are rejected.
7. Stop/revoke disables the bridge and invalidates the previous token.
8. ADB helper rejects zero or multiple authorized devices and cleans up its forward.
9. The skill ZIP contains no APK, JAR, native library, key, keystore, credential or other binary.
10. The skill's instructions match the actual implementation and are tested with at least one compatible agent before submission.

## Release rule

Do not submit the Agensi listing until the Android bridge, desktop helper, ZIP validation and end-to-end test all pass. The first listing should be free while compatibility and user experience are validated; pricing can be revisited after real usage.
