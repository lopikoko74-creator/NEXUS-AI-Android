# Reference assistant research for NEXUS

Reviewed: 2026-11-19

This document records feature and architecture research for NEXUS. The goal is to adapt proven capabilities to the existing Kotlin/Jetpack Compose + llama.cpp architecture and the user's on-device Qwen 3 4B model, not to combine unrelated projects wholesale.

## Capability findings

| Project | Useful capabilities | Main implementation approach | License / reuse decision |
|---|---|---|---|
| [Jarvis](https://github.com/judejulius/jarvis) | One-breath wake-word commands, interruptible speech, continuous follow-up conversation, offline wake-word and streaming ASR, Accessibility-based screen automation, screenshots for vision, multiple model providers with failover, spoken clarification, app onboarding and crash reports | Native Kotlin Android; sherpa-onnx keyword spotting and Zipformer ASR; Kokoro local TTS; Accessibility service; typed agent tools; OpenAI-compatible providers and Anthropic SDK | MIT. Code may be adapted with copyright/license notices and dependency/model licenses preserved. Best source for voice-pipeline and Android-agent implementation patterns. |
| [Sanna](https://github.com/sannabotdev/sannabotapp) | Multi-step tasks, background sub-agents, notification and schedule automation, app-specific learned UI hints, structured personal memory, editable assistant persona, Markdown skills, email/calendar/messaging integrations | React Native + native Kotlin; OpenAI/Claude; OAuth PKCE; local storage; tool/agent loop and sub-agents | README declares MIT. Reuse only compatible, clearly licensed components with attribution; adapt the architecture because the current NEXUS app is native Kotlin/Compose. |
| [Personal AI Assistant / Naomi](https://github.com/mukeshchandu/Personal-AI-Assistant) | Offline command router for timers, alarms, calls, messages, maps, music and device controls; Vosk wake word; multi-turn voice follow-up; cloud intent parsing with local Gemma fallback | Native Android; Vosk; SpeechRecognizer; offline command router; Groq/Gemini; optional local Gemma | No LICENSE file was found in the repository root during review. Do not copy its code unless an explicit license is added or permission is obtained. Public feature descriptions can inform requirements. |
| [Faraday](https://github.com/battlesbudz/Faraday) | Full-duplex voice and barge-in, automatic editable local memory, phone/media tools, saved/private conversations, pipeline timings and benchmark export, local vision direction | Kotlin + Compose; Gemma through LiteRT-LM; Whisper ASR; Piper TTS; on-device tool calling | PolyForm Noncommercial 1.0.0. Do not transplant code into a distributable/commercial product without reviewing the license and obtaining any required permission. Its runtime is also different from NEXUS's llama.cpp runtime. Use as a design reference for now. |

## NEXUS implementation decisions

1. **Keep the current core runtime initially.** NEXUS already has Kotlin/Compose, a typed action model, Accessibility automation, a bounded agent loop, native llama.cpp and GGUF loading. Replacing the runtime with LiteRT-LM or React Native would add migration risk before the existing voice pipeline is reliable.
2. **Make wake-word handling one-breath capable.** A single recognition result containing both the wake word and the command must preserve and dispatch the words after the wake word. A regression test now covers this.
3. **Separate instant commands from model planning.** App launch, media, navigation, timers and common device actions should use deterministic local handlers when their target and parameters are clear. Reserve the LLM for ambiguous or multi-step tasks.
4. **Upgrade speech as a pipeline, not a single recognizer call.** Evaluate sherpa-onnx streaming ASR and keyword spotting, voice activity detection, echo cancellation, barge-in, and chunked TTS. Keep Android's built-in recognizer as a fallback until the on-device models are bundled/downloaded and measured on the POCO F6.
5. **Add continuity and persistent memory deliberately.** Store task state separately from long-term user memory; make memory review/edit/delete explicit; resume interrupted tasks from verified observations rather than replaying completed actions.
6. **Use a typed, permission-aware tool registry.** Each action must have a schema, risk class, preconditions, confirmation policy, timeout, and observable result. High-impact actions (sending, calling, deleting, purchases, account/security changes) must require explicit confirmation.
7. **Add reliability instrumentation.** Record timings for wake detection, speech finalization, model load, inference, each tool action, and TTS first audio. Export a redacted diagnostic report so slow stages can be isolated.
8. **Automate verification.** The repository's Android CI workflow runs unit tests and assembles a debug APK. New parser and agent tests should be added to that gate before a release is published.

## Immediate development order

- Voice correctness: one-breath wake word, no dropped commands, serialized microphone sessions, timeout recovery.
- Responsiveness: deterministic fast-path commands, model warm-up/load state, bounded prompts and generation, cancellation.
- Voice quality: streaming ASR, echo cancellation and barge-in, then chunked neural TTS if device performance permits.
- Device control: safe typed tools for settings, media, volume, flashlight, timers and navigation; then app-specific workflows.
- Continuity: follow-up turns, persistent task checkpoints, local editable memory and scheduled routines.
- Quality: automated tests, CI APK, device test matrix on POCO F6, battery and latency measurements, signed release.

## Important limitations

- Repository README feature claims are not equivalent to independently tested behavior on the POCO F6.
- Model and voice assets have their own licenses, size, RAM and performance requirements; a code license does not grant model rights.
- Always-on microphone operation is constrained by Android foreground-service and background-start rules. It must remain user-controlled, visible and stoppable.
- Accessibility actions depend on the target app's UI and must be re-observed after every action. The agent must never infer success from issuing a tap alone.
