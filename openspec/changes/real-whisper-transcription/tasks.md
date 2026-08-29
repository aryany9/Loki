## 1. Runtime selection (spike findings already recorded in design.md)

- [ ] 1.1 Evaluate and select the inference runtime: LiteRT runtime AAR (`com.google.ai.edge.litert:litert-*`) vs tensorflow-lite ≥2.17, verifying stablehlo composite support (`odml.group_norm`, `odml.scaled_dot_product_attention`) on device; document choice in design.md

## 2. Real transcription in LiteRtWhisperEngine

- [ ] 2.1 Add the selected runtime dependency to `core/voice/stt/build.gradle.kts`
- [ ] 2.2 Implement log-mel spectrogram frontend (16 kHz PCM → `[1,80,3000]` f32 features) and bundle the multilingual Whisper tokenizer
- [ ] 2.3 Implement `initialize()` to construct the interpreter from the `.tflite` artifact; `isInitialized = true` only on success; load failure returns false with a logged reason
- [ ] 2.4 Implement `transcribePcmAudio()`: one encoder run, autoregressive whole-sequence decode loop (≤128 tokens, causal mask, `<|startoftranscript|><|en|><|transcribe|>` prefill, stop at end-of-transcript); remove the `"Voice command received"` placeholder
- [ ] 2.5 Log transcription duration and emit `FinalResult("")` for empty/near-silence output
- [ ] 2.6 Add/adjust unit tests: engine reports uninitialized after failed load; transcription emits varying non-constant transcripts (fake backend or instrumented test as feasible)

## 3. Readiness accuracy (engine-side only — no AssistantSession changes)

- [ ] 3.1 Make `ModelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_ASR)` reflect the STT engine's actual `isInitialized` state (the existing session precheck then fails fast with a clean Error when the engine isn't ready)

## 4. Device validation

- [ ] 4.1 Build and install on device; verify first-turn-after-load no longer throws `Whisper model not initialized` (clean Error or success via accurate readiness)
- [ ] 4.2 Via adb logcat, verify transcripts vary with spoken input and the assistant's TTS responses no longer repeat a fixed greeting
- [ ] 4.3 Verify error path: invoke assistant with STT model removed/not loaded → Error state shown in overlay
- [ ] 4.4 Confirm no regression when the later `voice-input-strategies` change lands: readiness signal consumed unchanged by its strategy-aware precheck
