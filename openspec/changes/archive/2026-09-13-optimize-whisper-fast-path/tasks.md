## 1. Catalog & model schema

- [x] 1.1 Add optional `variant` field (`quantized` / `full-precision`) to `ModelArtifact` in `core/models` (backward-compatible, nullable, ignored by old consumers)
- [x] 1.2 Update `core/models/src/main/assets/model_catalog.json`: add `whisper_tiny_30s_i8.tflite` artifact under `whisper-tiny-litert` (size 41116288, sha256 `6748ac565a228c4a00b18d11ea1e2fd7cead3db6fba94e3f0bf35756b13ba4a9`, url `https://huggingface.co/litert-community/whisper-tiny/resolve/main/whisper_tiny_30s_i8.tflite`, variant `quantized`); tag the existing f32 artifact as `full-precision`

## 2. Engine acceleration & variant resolution

- [x] 2.1 In `LiteRtWhisperEngine`, replace `setUseXNNPACK(false)` with a named companion constant (e.g. `USE_XNNPACK = true`) wired into `Interpreter.Options`
- [x] 2.2 Implement variant-aware artifact resolution in `load()`: prefer `variant == "quantized"` present on disk → fall back to `full-precision` → fail; remove the hardcoded `whisper_tiny_30s_f32.tflite` filename fallback and replace with variant-aware resolution
- [x] 2.3 Verify `ModelLibraryManager` readiness/download flow provisions the int8 artifact lazily without blocking existing f32 installs; adjust only if the download path requires variant awareness

## 3. Tests

- [x] 3.1 Unit test: variant resolution prefers quantized when both files exist, falls back to full-precision when only f32 exists, returns false when neither exists
- [x] 3.2 Unit test: catalog parsing accepts records with multiple variants and preserves integrity metadata
- [x] 3.3 Regression check: `VoiceInputStrategyResolver` tests still pass unchanged (readiness semantics untouched)

## 4. Validation & wrap-up

- [ ] 4.1 On-device A/B check: transcribe a fixed utterance set with f32/XNNPACK-off vs int8/XNNPACK-on; confirm latency improvement and comparable accuracy; record timings in change notes (Deferred: no device attached)
- [x] 4.2 Confirm `DIRECT_AUDIO` path (audio-capable Gemma) is unaffected; confirm ChatViewModel transcript-recovery path picks up int8 readiness
- [x] 4.3 Update `temp/readme.md` status table (item 1c → DONE) and archive the change
