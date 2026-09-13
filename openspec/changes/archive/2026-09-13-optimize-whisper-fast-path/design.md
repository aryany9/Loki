## Context

- `LiteRtWhisperEngine` (core/voice/stt) constructs a TFLite `Interpreter` with `setUseXNNPACK(false)` and a fixed `NUM_THREADS = 2`.
- The ASR catalog entry (`whisper-tiny-litert`) carries a single `whisper_tiny_30s_f32.tflite` artifact (150,979,184 bytes). The engine also hardcodes the f32 filename as a fallback (`storage.artifactFile("whisper-tiny-litert", "whisper_tiny_30s_f32.tflite")`).
- Voice strategy resolution (`VoiceInputStrategyResolver`): audio-capable LLMs take `DIRECT_AUDIO`; Whisper only runs on the `STT_TRANSCRIBE` path (text-only LLMs) and in ChatViewModel transcript recovery. The planned compact model tier is text-only, so Whisper becomes the main voice path on low-RAM devices after model tiering.
- An official quantized variant exists upstream: `litert-community/whisper-tiny` → `whisper_tiny_30s_i8.tflite`, 41,116,288 bytes, sha256 `6748ac565a228c4a00b18d11ea1e2fd7cead3db6fba94e3f0bf35756b13ba4a9`, Apache-2.0. Same multi-subgraph structure (encoder/decoder subgraphs); inputs/outputs remain f32 with int8 weights, so the existing mel frontend, tokenizer, and subgraph dispatch are unchanged.
- Model downloads are catalog-driven and already verify sha256; artifacts resolve through `ModelStorage.artifactFile(modelId, relativePath)`.

## Goals / Non-Goals

**Goals:**
- Cut STT transcription latency to ~300–500ms on mid-range hardware and shrink STT RAM/download footprint ~3.6x.
- Prefer the int8 artifact automatically; keep f32 as a working fallback (no user-facing setting required).
- Keep `VoiceInputStrategyResolver` semantics unchanged (readiness simply succeeds faster and lighter).

**Non-Goals:**
- Streaming/partial transcription (separate change).
- NPU/Qualcomm/MediaTek-optimized Whisper variants (separate change; the upstream repo ships SoC-specific f32 builds that can be added later).
- Compact LLM tiering or RAM-based model defaults.
- Retraining or re-quantizing the model ourselves.

## Decisions

### D1: Enable XNNPACK (flag change)
Replace `setUseXNNPACK(false)` with `setUseXNNPACK(true)` on the `Interpreter.Options`. XNNPACK dispatches the conv/matmul-heavy encoder and decoder ops to NEON-optimized kernels. Risk is low: XNNPACK is enabled by default in modern TFLite and is the widely used CPU fast path. If a specific device shows a correctness problem (e.g. denormal-free fp accumulation), we gate the flag behind a build-config constant rather than reverting silently.
*Alternative considered: NNAPI/GPU delegates — rejected for now: the multi-subgraph `odml.*` composite ops (stablehlo composites) are not reliably supported on delegates, and SoC-specific upstream artifacts are the intended NPU path (later change).*

### D2: Artifact variants in the catalog, not separate model IDs
Add `whisper_tiny_30s_i8.tflite` as a second artifact under the existing `whisper-tiny-litert` record, tagged with a new optional `variant` field (`"quantized"` vs `"full-precision"`). Introduce `ModelArtifact.variant` (nullable) in `core/models`.
- Why not a separate model ID: it preserves the single `LITERT_ASR` runtime slot, existing readiness providers, active-model manifest entries, and `VoiceInputStrategyResolver` logic — no plumbing changes.
- Selection rule in `LiteRtWhisperEngine.load()`: prefer artifact with `variant == "quantized"` that exists on disk; otherwise fall back to `full-precision`; otherwise fail as today. Remove the hardcoded f32 filename fallback in favor of variant-aware resolution.

### D3: Lazy download of the int8 artifact
Do not force-download int8 at setup for existing installs. The int8 artifact downloads through the existing catalog-driven flow when the ASR runtime is provisioned (only reached when a text-only LLM needs voice, or on first transcript-recovery demand). Devices that already have f32 keep working immediately; int8 is fetched opportunistically on the next ASR provisioning cycle.
*Alternative considered: immediate migration/delete-f32 — rejected: forces a ~45MB download on users who never use the STT path and risks breaking voice if the download fails.*

### D4: Keep thread count at 2, measure before touching
`NUM_THREADS = 2` was presumably tuned for thermals/battery on the background dispatcher. Increasing threads may help latency but increases contention with the LLM engine threads during prefill. Measure with XNNPACK + int8 first; only revisit if the 300–500ms target is missed.

## Risks / Trade-offs

- [int8 WER regression on noisy/noisy-speech input] → keep f32 artifact installed and downloadable; selection rule falls back cleanly; validation includes a manual A/B transcription check on a fixed utterance set before archiving the change.
- [XNNPACK numerics differences] → gated behind a named constant; fallback to previous behavior is a one-line change.
- [Existing installs keep 150 MB f32 on disk] → acceptable short-term; storage reclamation can ride along with the future model-tiering change.
- [New `variant` field on artifacts] → schema versioned (`schemaVersion: 1` consumers treat it as optional); older code paths that do `firstOrNull { endsWith(".tflite") }` still work (first artifact remains resolvable).

## Migration Plan

1. Ship catalog update + engine changes together (single release).
2. On update: existing users with f32 installed continue transcribing as before (fallback path).
3. Next time the ASR model is (re)provisioned or int8 is fetched lazily, the engine loads int8 preferentially.
4. Rollback: reverting the engine change restores f32-only behavior; catalog entry is backward compatible.

## Open Questions

- None blocking. (Post-implementation: whether SoC-specific Qualcomm/MediaTek f32 builds should become device-matched variants — deferred to the NPU/SoC Whisper follow-up change.)

## Post-Implementation Addendum (2026-09-13)

**D1 revised — XNNPACK disabled after on-device repro.** Original D1 planned to enable XNNPACK. On device, `setUseXNNPACK(true)` + the Whisper artifact caused a reproducible native **SIGSEGV (SEGV_ACCERR)** inside `NativeInterpreterWrapperExperimental.<init>` during interpreter construction — uncatchable in Kotlin, killing the process the moment the int8 model loaded. Root cause: the artifact is a multi-subgraph model whose group_norm/SDPA ops are `odml.*` stablehlo composites, which XNNPACK cannot dispatch in the bundled runtime.

Isolation evidence:
- XNNPACK on + int8 → native crash, twice, identical fault signature
- XNNPACK off + int8 → loads cleanly (225ms init, signatures `[encode, decode]`), transcribes fine

Resolution: `USE_XNNPACK = false` with a root-cause comment guarding silent reintroduction; the spec's XNNPACK requirement was rewritten accordingly. The quantization win stands (download 150MB → 41MB, RAM ~150MB → ~45MB, faster int8 kernels on generic CPU path). XNNPACK for this artifact is deferred until a runtime version that skips `odml.*` composite ops is available — revisit via the SoC-specific Whisper variants change.
