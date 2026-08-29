## Why

The voice assistant always responds with the same greeting regardless of user speech. Investigation (adb logcat, session 2026-08-29 19:50) showed the STT pipeline transcribes every utterance to the hardcoded string `"Voice command received"` — `LiteRtWhisperEngine.transcribePcmAudio()` is a placeholder that never runs the Whisper model. Additionally, the assistant can be invoked before the Whisper engine is initialized (two consecutive turn failures with `IllegalStateException: Whisper model not initialized`), because `isRuntimeReady(LITERT_ASR)` does not reflect the engine's actual state.

## What Changes

- Implement real on-device transcription in `LiteRtWhisperEngine.transcribePcmAudio()` using the Whisper inference path on the downloaded artifact (spike findings below).
- Make `LiteRtWhisperEngine.initialize()` perform actual model instantiation instead of only checking file existence.
- Fix the readiness race: `ModelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_ASR)` must report the engine's true initialized state. No changes to `AssistantSession` — the existing precheck becomes correct once readiness is accurate.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `voice-pipeline`: STT stage must produce real Whisper transcripts (no placeholder output) and report true initialization state so runtime readiness is accurate; failure to initialize surfaces as an Error state instead of a silent/greedy fallback.

## Impact

- `core/voice/stt/src/main/java/dev/loki/android/core/voice/stt/LiteRtWhisperEngine.kt` — real inference + initialization.
- `core/models` (`ModelLibraryManager` / runtime readiness logic) — accurate LITERT_ASR readiness.
- `core/voice/stt` `build.gradle.kts` — likely a new LiteRT runtime dependency (see design Decisions).
- **Deliberately out of scope**: any `AssistantSession` changes (strategy-aware precheck, WAV packaging, audio turn sending) — those belong to the `voice-input-strategies` change. This change is self-contained: fix the STT engine, fix readiness accuracy, done.
