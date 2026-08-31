# fix-chat-input-and-voice

## Why

Two user-facing chat defects: (1) tapping the composer TextField pans the whole window up to the top bar (missing `adjustResize`), making chat feel like a search box; (2) the composer mic button silently does nothing — it hardwires the STT transcribe path (never-initialized whisper) instead of using the model-capability-driven voice strategy that already works in the assistant (DIRECT_AUDIO for audio-capable models, i.e. no whisper needed at all).

## What Changes

- Add `android:windowSoftInputMode="adjustResize"` to `MainActivity` in the manifest so the IME resizes the window instead of panning it (Compose `imePadding` already handles insets).
- Extract the voice strategy decision from `AssistantSession` into a shared resolver (`VoiceInputStrategyResolver`) that returns DIRECT_AUDIO, STT_TRANSCRIBE, or UNAVAILABLE based on the active model's `capabilities.isAudioInputSupported` (VERIFIED/USER_CONFIRMED) and STT runtime readiness.
- Rewire `ChatViewModel.startVoiceInput()` through the resolver:
  - DIRECT_AUDIO: record via `AudioRecorder` → `WavEncoder` → `processUtterance(userInput = "", audioBytes = wav)` through the existing chat session (streaming/tool pipeline reused as-is).
  - STT_TRANSCRIBE (text-only models): initialize whisper from model storage, transcribe, send text.
  - UNAVAILABLE: visible in-chat error message (no silent failure).
- Surface `SttEvent.Error` and turn errors in the chat UI instead of silently resetting the recording flag.
- Out of scope: in-chat waveform/recording UI beyond the existing stop-state, assistant-mode changes, model provisioning UI.

## Capabilities

### New Capabilities
- `voice-strategy-resolution`: Shared model-capability-driven resolution of voice input strategy (DIRECT_AUDIO / STT_TRANSCRIBE / UNAVAILABLE) used by both chat and assistant paths.

### Modified Capabilities
- `chat-ui`: Mic button SHALL use the capability-driven voice strategy and SHALL surface voice errors visibly.

## Impact

- **Code**: `app/src/main/AndroidManifest.xml` (one attribute), new `VoiceInputStrategyResolver` (core/assistant), new `core/theme` module (theme package moved out of core/ui — see design D2), `ChatViewModel` (strategy wiring, error surfacing), `AssistantSession` (reuse resolver), `ChatScreen` (error display), `AgentConfigRepository` (DataStore import update).
- **Dependencies**: none new (theme module uses existing Compose/DataStore artifacts).
- **Specs**: new `voice-strategy-resolution`; `chat-ui` delta (mic button behavior).
- **Risk**: recording during streaming (UI allows mic only when idle — existing morphing button state already enforces this); RECORD_AUDIO permission already declared and granted via setup.
