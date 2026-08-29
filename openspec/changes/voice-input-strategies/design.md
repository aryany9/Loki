## Context

Loki supports downloading arbitrary `.litertlm` models, and the voice pipeline must serve both audio-capable models (Gemma 4 E4B IT confirmed: `Content.AudioBytes` API in litertlm 0.16.1, audio template/USM encoder embedded in the artifact) and text-only models behind Whisper STT. Today: `ModelRecord` has no capability metadata (catalog capability tags are dropped at registration), `LiteRtLlmEngine` hardcodes `supportsAudioInput = false`, sends text-only `Message.user(prompt)`, and omits `audioBackend` from `EngineConfig`. `AssistantSession.startTurn()` unconditionally requires `LITERT_ASR` readiness. `AudioRecorder` already captures 16 kHz PCM 16-bit.

AI Edge Gallery reference (audio-capable path): PCM 16-bit @ 16 kHz wrapped in a 44-byte WAV header → `Content.AudioBytes(bytes)` + `Content.Text(input)` (text last) → `sendMessageAsync(Contents.of(...))`; `EngineConfig.audioBackend = Backend.CPU()` while the main backend stays GPU.

## Goals / Non-Goals

**Goals:**
- Capability metadata on the model record, with confidence levels; capability decides strategy — never the engine.
- Two voice-input strategies selected per turn: direct-audio and STT-transcribe.
- Direct-audio path participates fully in the ReAct JSON tool-calling loop.
- 30 s recording cap for both strategies.
- Auto-demotion: wrong-capability failure at the model boundary retries once via STT fallback.
- Whisper remains a permanent fallback; ASR readiness required only when STT strategy is selected.

**Non-Goals:**
- No `.litertlm` artifact probing in v1 (deferred; catalog + user toggle only).
- No removal or rewrite of the Whisper stage (see parallel `real-whisper-transcription` change).
- No vision-input support, no HF model-card metadata parsing.
- No streaming partial transcripts for the audio path.

## Decisions

1. **Capability lives in `ModelRecord`** as `ModelRecordCapabilities` (confidence-tagged `audioInput` via the existing `ModelMetadataField` pattern). Precedence: USER_CONFIRMED (import toggle) > VERIFIED (bundled catalog) > UNKNOWN (treated as text-only). Rationale: user-confirmed claims trump curated ones; a false "audio" claim is worse than a false negative because it fails the turn, so the toggle defaults to false (Gallery's approach).
2. **Strategy is selected per turn in the voice pipeline** (`AssistantSession`/turn orchestration) by reading the active LLM record's capability. A `VoiceInputStrategy` abstraction (direct-audio vs stt-transcribe) keeps `if (supportsAudio)` out of the conversation layers.
3. **Direct-audio goes through the ReAct tool loop** (decision 1b). The same system prompt and JSON protocol govern; the audio bytes are part of the user turn sent to the LLM. Consequence: no app-visible transcript on this path — turn logging records the strategy and recording metadata (duration, demotion events) instead of transcript text.
4. **`EngineConfig.audioBackend = Backend.CPU()` is passed unconditionally** — harmless for text-only models and avoids engine re-initialization when the user switches between audio-capable and text-only models.
5. **Failure demotion (5b)**: if the direct-audio turn fails at the model boundary (e.g. model rejects audio), retry the same turn once through the STT strategy when Whisper is loaded; log the demotion and reflect it in `AssistantState`. If Whisper is unavailable, surface the Error state.
6. **Recording cap 30 s for both strategies** — matches the Whisper fixed window and Gallery's audio clip limit; bounds audio-token KV consumption.
7. **Model switching**: LLM and ASR remain independent runtimes in `activeModels`; switching the LLM re-evaluates strategy on the next turn and never ejects the ASR model. Setup-completion gating on LITERT_ASR becomes capability-aware.

## Risks / Trade-offs

- [Audio prefill latency on the CPU audio encoder (30 s clip)] → hard 30 s cap; log strategy timings; STT fallback keeps a fast path for text-only models.
- [Audio + ReAct JSON in one pass is untested territory] → tool-loop parity validated on device with the gemma-4 model first; demotion path provides a working fallback if quality is poor.
- [No transcript visibility on the audio path hurts auditability] → turn logs record strategy + metadata; demotion events are explicit.
- [Wrong USER_CONFIRMED flags cause failed turns] → toggle defaults false; one-retry demotion masks single misconfigurations.
- [Manifest schema change] → additive field with defaults; `ModelRegistry` JSON uses `ignoreUnknownKeys`; no migration break expected, but bump awareness needed.

## Migration Plan

Additive metadata + new engine/plumbing code; no data migration beyond manifest backward compatibility. Rollback = revert. Validate on device: gemma-4 E4B IT (direct-audio turns incl. tool calls), text-only model (STT turns), switch between them mid-session, forced-bad-flag demotion.

## Open Questions

(none — strategy, capability source, tool-loop parity, cap, and demotion semantics are all decided)
