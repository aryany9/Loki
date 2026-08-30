## Tasks

- [x] 1. Extend Model Library registry (`ModelTypes.kt` / `ModelStorage.kt`) to support loading and storing `.tflite` ASR model files under `ModelRuntime.LITERT_ASR`.
- [x] 2. Create `LiteRtWhisperEngine.kt` in `core:voice:stt` implementing `SttEngine`, adapting LiteRT CompiledModel execution for `whisper_tiny_30s_f32.tflite` and consuming `AudioRecorder.kt` PCM/VAD output.
- [x] 3. Refactor `AssistantSession.kt` in `core:assistant` to coordinate the production pipeline: Invocation ──▶ Audio Capture / VAD ──▶ LiteRtWhisperEngine ──▶ Voice Session (`maxTurns = 1`) ──▶ LiteRtLlmEngine ──▶ Tools ──▶ AndroidTtsEngine ──▶ Audio Playback ──▶ Idle.
- [x] 4. Implement end-to-end multi-stage cancellation in `AssistantSession.cancelTurn()` stopping STT recording, LLM generation (`cancelProcess()`), tool coroutines, and TTS playback (`ttsEngine.stop()`), safely resetting overlay to `Idle`.
- [x] 5. Implement clean unrecoverable error handling across STT, LLM, Tool execution, TTS, model loading, and microphone permission stages, ensuring resources are cleaned up and state returns to `Idle`.
- [x] 6. Add unit tests for `LiteRtWhisperEngine`, `AssistantSession` cancellation across STT/LLM/tool/TTS stages, single-turn voice session policy (`maxTurns = 1`), and error-to-Idle recovery.
