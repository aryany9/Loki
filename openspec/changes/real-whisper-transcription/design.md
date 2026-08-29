## Context

The voice assistant pipeline (mic capture → VAD → STT → LLM → TTS) is fully wired, but `LiteRtWhisperEngine.transcribePcmAudio()` is a placeholder that returns the hardcoded string `"Voice command received"` (LiteRtWhisperEngine.kt:124). Every voice turn therefore sends the same fake transcript to the LLM, which replies with a generic greeting — the exact symptom the user observed ("I'm ready. How can I help you today?" every time).

Additionally, `initialize()` (lines 61–70) only checks that the model file exists and sets a flag; no interpreter is constructed, and the native handle field is commented out (line 31). A startup race was observed on device: the first two assistant invocations failed with `IllegalStateException: LiteRT Whisper model not initialized` even though `AssistantSession.startTurn()` prechecks `modelManager.isRuntimeReady(ModelRuntime.LITERT_ASR)` — the readiness signal does not reflect the engine's real state.

**Spike findings (resolved by artifact inspection, 2026-08-29):** the downloaded `whisper_tiny_30s_f32.tflite` (144 MB, f32, TFL3, min runtime 2.17.0) is a multi-subgraph LiteRT export: 36 subgraphs, of which subgraph 0 is the encoder (mel input `[1,80,3000]` f32 → `[1,1500,384]`), subgraph 1 the decoder (encoder output + int32 token sequence `[1,128]` + attention mask `[1,1,128,128]` → logits `[1,128,51865]`), and 34 are `odml.*` composite implementations (group_norm, SDPA) requiring a LiteRT runtime with stablehlo composite support ≥ 2.17. No tokenizer is embedded; the vocab is multilingual (51865). The `litertlm-android` AAR in the project contains **no** general TFLite interpreter — a new runtime dependency is required for the tflite route (or the vendored whisper.cpp route with a GGML artifact).

This change is deliberately decoupled from `voice-input-strategies`: it touches only the STT engine internals and readiness accuracy. The voice-input-strategies change will make the session precheck strategy-aware on top of the accurate readiness signal fixed here; nothing done here will be redone there.

## Goals / Non-Goals

**Goals:**
- Real on-device Whisper transcription of recorded PCM audio.
- `initialize()` constructs a usable interpreter (or fails with a real error).
- `isRuntimeReady(LITERT_ASR)` reflects the engine's true state, making the existing session precheck correct.
- Failure surfaces as `AssistantState.Error` with a meaningful message (no silent fake transcripts).

**Non-Goals:**
- Any `AssistantSession` changes (strategy-aware precheck, demotion, WAV packaging) — owned by `voice-input-strategies`.
- Streaming/partial Whisper decoding (existing VAD + fixed 30s window design stays).
- Changing `SttEngine` interface, VAD behavior, or the conversation/LLM layers.
- Switching STT backends or model variants.

## Decisions

1. **Run inference on the tflite artifact via a LiteRT runtime interpreter** inside `transcribePcmAudio()` (Dispatchers.Default). Requires adding a LiteRT runtime dependency (with stablehlo composite support ≥ 2.17) since `litertlm-android` exposes no interpreter. Inference is multi-step: one encoder run, then autoregressive whole-sequence decode loops (up to 128 tokens) with causal masks; requires in-app log-mel frontend and bundled multilingual tokenizer. The fixed 30 s padded window the app already produces matches the encoder input exactly.
2. **`initialize()` constructs the interpreter eagerly** and sets `isInitialized` only on success. Errors propagate as load failure (return `false` / throw with message).
3. **Readiness is authoritative from the engine**: `ModelLibraryManager.isRuntimeReady(LITERT_ASR)` delegates to the STT engine's `isInitialized`. The existing `AssistantSession` precheck then works unchanged — no session modifications in this change.
4. **Empty/near-silence transcripts remain valid `FinalResult("")`** — the existing "No speech detected → Idle" path handles it.

## Risks / Trade-offs

- [New runtime dependency needed (litertlm has no interpreter)] → evaluate `com.google.ai.edge.litert` runtime AAR or tensorflow-lite ≥2.17; first task verifies composite-op support for `odml.*`.
- [Whisper inference latency on low-end devices (multi-step decode)] → keep fixed 30 s window, run on `Dispatchers.Default`, log transcription duration; no streaming in scope.
- [Log-mel frontend + tokenizer are non-trivial app-side components] → standard, well-documented Whisper preprocessing; tokenizer is a static bundled asset.
- [Readiness fix changes failure timing] → the precheck now correctly fails fast with a clear Error instead of a mid-turn crash; acceptable and observable.

## Migration Plan

Single-module change plus one dependency addition; no data migration. Rollback = revert. Validate on device with adb logcat: transcripts must vary with speech, no `not initialized` errors after engine load completes.

## Open Questions

- Confirm the exact LiteRT runtime artifact choice (e.g. `com.google.ai.edge.litert:litert-*` vs `org.tensorflow:tensorflow-lite:2.17+`) with composite-op support for `odml.group_norm` / `odml.scaled_dot_product_attention` — first implementation task resolves this.
