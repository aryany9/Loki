## Why

Loki downloads arbitrary `.litertlm` models from Hugging Face, but the voice pipeline assumes a text-only LLM behind a Whisper STT stage (currently a broken stub). Audio-capable models such as Gemma 4 E4B IT can receive recorded audio directly through LiteRT-LM `Content.AudioBytes` (API present in litertlm 0.16.1; audio support confirmed embedded in the artifact), but the engine integration hardcodes `supportsAudioInput = false` and text-only message sending. The architecture must support both voice-input strategies — direct audio for audio-capable models, Whisper STT for text-only models — with capability decided by model metadata, not the LLM engine.

## What Changes

- Add per-model capability metadata to `ModelRecord` (`ModelRecordCapabilities` with confidence-tagged `audioInput`), populated from structural `.litertlm` container inspection (`LitertLmContainerInspector`, VERIFIED), bundled catalog entries (VERIFIED), and user confirmation at import time (USER_CONFIRMED). Structural inspection parses the container header table (~first 64 KB) for `tf_lite_audio_encoder_hw` / `tf_lite_audio_adapter` components without relying on filename heuristics.
- Extend `LiteRtLlmEngine` to pass `audioBackend = Backend.CPU()` in `EngineConfig` and to support sending multimodal turns (`Content.AudioBytes` + `Content.Text`) via `sendMessageAsync(Contents.of(...))`; engine capability reporting becomes a function of the loaded model record.
- Introduce a per-turn `VoiceInputStrategy` selection in the voice pipeline: **direct-audio** when the active LLM model record declares audio input, **STT-transcribe** otherwise. Recording cap: 30 s, matching the Whisper window.
- Direct-audio path goes through the existing ReAct JSON tool-calling loop (full assistant parity). WAV packaging of 16 kHz PCM 16-bit per AI Edge Gallery's `genByteArrayForWav` pattern.
- Make the `LITERT_ASR` readiness precheck conditional: required only when the selected strategy is STT; skipped for direct-audio turns.
- Auto-demotion failure semantics: if a direct-audio turn fails at the model boundary (capability flag was wrong), retry the same turn once via STT fallback (when Whisper is loaded) and surface the demotion in logs/UI state.
- Keep Whisper as a permanent fallback — no removal of the STT stage.

## Capabilities

### New Capabilities

- `voice-input-strategies`: Per-turn selection between direct-audio and STT voice-input strategies based on the active model's capability metadata, including recording caps, failure demotion, and model-switch behavior.

### Modified Capabilities

- `voice-pipeline`: Voice turn precheck changes from unconditionally requiring LITERT_ASR readiness to requiring it only for the STT strategy; strategy selection based on active LLM capability metadata is added to the turn flow.
- `model-library`: Model records carry capability metadata (`ModelRecordCapabilities`) through registration from catalog and import paths, with confidence levels and user-confirmation semantics.

## Impact

- `core/models` — `ModelTypes.kt` (`ModelRecordCapabilities`, `ModelRecord.capabilities`), `ModelLibraryManager`/registration plumbing; manifest schema (version bump or additive field).
- `core/llm` — `LiteRtLlmEngine.kt` (audioBackend, multimodal message send, capability from record), `LlmEngine.kt` interface extension.
- `core/assistant` — `AssistantSession.kt` (strategy selection, conditional ASR precheck, demotion retry).
- `core/voice/stt` — unchanged behavior, but becomes an optional dependency of the voice feature (no code removal).
- `core/ui` / `app` — import-time capability toggle; capability-aware setup completion criteria.
- Depends on `real-whisper-transcription` change (parallel) for a working STT fallback path.
