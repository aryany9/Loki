## Why

Loki is a greenfield open-source Android voice assistant that processes requests entirely on-device using local AI. No code exists yet. This change establishes the foundational architecture — Android Assistant integration, local voice pipeline, LLM-powered tool routing, and the initial set of offline Android tools — so that the first working end-to-end prototype can be built and the highest-risk technical assumptions validated through targeted spikes.

## What Changes

- New Android application registered as a system-level Android Assistant via `ROLE_ASSISTANT` and `VoiceInteractionService`, invocable from the lock screen without device-specific hacks
- Local voice pipeline: microphone capture → VAD → on-device STT (Whisper.cpp) → local TTS (Android TTS)
- `SttEngine` abstraction with `WhisperSttEngine` as the first backend
- Local LLM inference layer abstracted behind `LlmEngine` / `ModelManager`, with `LlamaCppLlmEngine` as the first backend and `LiteRtLmEngine` retained as a second candidate
- Grammar-constrained structured output (GBNF) for reliable tool-call generation from small quantized models, with dynamic grammar generation from `ToolRegistry` at runtime
- Multi-turn `ConversationManager` implementing a bounded agent loop (ReAct-style): STT → LLM → tool call → tool result → LLM → TTS, with a fast path for simple single-tool requests
- `ToolRegistry` as the authoritative source for available tools, tool schemas, and semantic validation of LLM-produced tool calls
- Initial set of local Android tools covering the MVP: contacts lookup, call contact, dial number, open application, battery status, current time, set timer, set alarm, basic media controls
- Explicit separation of `LocalTool` and `OnlineTool` interfaces, with online tools deferred to a future phase
- Minimal Jetpack Compose overlay UI rendered within the `VoiceInteractionSession` window (listening indicator, partial transcript, processing state, response text)
- `ContextProvider` abstraction with `ConversationContext` and `DeviceContext`; `ScreenContext` via `AssistStructure` deferred to a future phase
- `WakeWordEngine` interface defined but unimplemented — wake-word detection is an optional future subsystem
- Three technical spikes to validate the highest-risk assumptions before full implementation begins

## Capabilities

### New Capabilities

- `android-assistant-integration`: Registration and lifecycle of Loki as the Android default assistant via `ROLE_ASSISTANT`, `VoiceInteractionService`, and `VoiceInteractionSessionService`, including lock-screen/keyguard invocation and session lifecycle management
- `voice-pipeline`: End-to-end local voice I/O — microphone capture, VAD, STT (`SttEngine`/`WhisperSttEngine`), and TTS (`TtsEngine`/`AndroidTtsEngine`) with streaming support, cancellation, and lifecycle management
- `llm-engine`: Abstracted local LLM inference layer — `LlmEngine`, `ModelManager`, `LlamaCppLlmEngine` (first backend), structured/streaming generation, grammar-constrained output, context management, cancellation
- `tool-registry`: Central registry and validation layer for all assistant tools — `Tool` interface, `ToolRegistry`, `ToolResult`, semantic argument validation, permission-gating, `LocalTool` / `OnlineTool` separation
- `conversation-manager`: Multi-turn agent loop managing conversation context, turn history, bounded tool-iteration loop, fast single-tool path, cancellation, context-budget tracking, and session reset
- `local-android-tools`: Initial MVP tool implementations: `LookupContactTool`, `CallContactTool`, `DialNumberTool`, `OpenAppTool`, `GetBatteryTool`, `GetTimeTool`, `SetTimerTool`, `SetAlarmTool`, `MediaControlTool`
- `grammar-builder`: Dynamic GBNF grammar generation from `ToolRegistry` schemas for grammar-constrained LLM output; guarantees syntactic validity of tool calls independent of semantic validation
- `voice-interaction-ui`: Minimal Jetpack Compose overlay surface displayed within `VoiceInteractionSession` — session state indicator, partial transcript, processing state, response text

### Modified Capabilities

_(None — this is a greenfield project with no existing specs.)_

## Impact

- **New Android application** — no existing codebase affected
- **Native dependencies**: llama.cpp via JNI/CMake (ARM64), whisper.cpp via JNI/CMake (ARM64)
- **Android permissions introduced**: `RECORD_AUDIO`, `READ_CONTACTS`, `CALL_PHONE`, `MANAGE_MEDIA`, `SET_ALARM`, `VIBRATE`, `FOREGROUND_SERVICE`; all requested on-demand, not at install time
- **Minimum SDK**: API 29 (Android 10); compile SDK: latest stable at implementation time
- **Architecture constraint**: No Samsung-specific APIs; standard Android APIs only; Samsung device used for primary testing only
- **LLM model storage**: GGUF models stored on device; `ModelManager` handles download, storage, and lifecycle
- **Three pre-implementation technical spikes** must complete and pass before full feature implementation begins:
  1. VoiceInteractionService + lock-screen invocation on target device
  2. llama.cpp on Android + grammar-constrained tool calling reliability
  3. Whisper STT + Android TTS end-to-end latency
