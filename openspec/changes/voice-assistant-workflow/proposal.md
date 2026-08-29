## Why

While Chat Mode and LLM engine capabilities (`LiteRtLlmEngine`, `AgentConfig`) are functional, Loki's primary purpose—a local on-device Voice Assistant—requires completing the end-to-end voice pipeline. Replacing the old `whisper.cpp` spike with a LiteRT-based Whisper STT engine (`LiteRtWhisperEngine`), integrating energy VAD, enforcing single-turn voice sessions (`maxTurns = 1`), enabling dual TTS selection (`AndroidTtsEngine` / `CustomModelTtsEngine`), and building multi-stage cancellation will complete Loki's Voice Assistant architecture.

## What Changes

- Implement `LiteRtWhisperEngine` in `core:voice:stt`, adapting the official Google AI Edge LiteRT ASR reference (`whisper_tiny_30s_f32.tflite`) into Loki's `SttEngine` interface.
- Store and manage the Whisper `.tflite` model using Loki's existing Model Library storage (`ModelRecord` / `ModelStorage` with `ModelRuntime.LITERT_ASR`).
- Connect 16kHz mono audio recording and energy VAD from `AudioRecorder.kt` to `LiteRtWhisperEngine`, applying fixed 30-second window padding/preprocessing.
- Wire the complete end-to-end Voice Assistant pipeline:
  `Invocation` ──▶ `Audio Capture & VAD` ──▶ `LiteRtWhisperEngine` ──▶ `Voice Agent Session (maxTurns=1)` ──▶ `LiteRtLlmEngine` ──▶ `Tool Execution` ──▶ `TtsEngine (System / Custom)` ──▶ `Audio Playback` ──▶ `Idle`.
- Preserve strict distinction between Voice Mode (`maxTurns = 1`, low latency, no long-term chat history auto-pull) and Chat Mode (persistent multi-turn context).
- Implement multi-stage cancellation in `AssistantSession.cancelTurn()` propagating through STT, LLM generation (`cancelProcess()`), tool execution, and TTS playback (`ttsEngine.stop()`).
- Handle offline execution, permission checks, missing microphone permissions, and model-not-loaded states gracefully in the voice session overlay.

## Capabilities

### New Capabilities

- `voice-assistant-workflow`: LiteRT-based Whisper STT (`LiteRtWhisperEngine`), end-to-end voice assistant session orchestrator, single-turn voice policy (`maxTurns = 1`), dual TTS provider integration, and multi-stage session cancellation.

### Modified Capabilities

- `voice-pipeline`: Updated to replace native C++ `whisper.cpp` with LiteRT ASR execution and full end-to-end cancellation.

## Impact

- `core:voice:stt`: Add `LiteRtWhisperEngine.kt` and integrate LiteRT CompiledModel execution for `whisper_tiny_30s_f32.tflite`.
- `core:assistant`: Refactor `AssistantSession.kt` to coordinate the full production pipeline, voice overlay states, and cancellation.
- `core:voice:tts`: Support provider selection between `AndroidTtsEngine` and `CustomModelTtsEngine`.
- Dependencies: Add LiteRT ASR runtime dependency (`com.google.ai.edge.litert:litert-android` / `litertlm-android`).
