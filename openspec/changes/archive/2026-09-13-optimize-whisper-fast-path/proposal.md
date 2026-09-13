## Why

On-device Whisper transcription currently takes ~1.5–3s on mid-range hardware because `LiteRtWhisperEngine` runs the unquantized 150 MB `f32` model with XNNPACK explicitly disabled (`setUseXNNPACK(false)`). Whisper is not the main voice path today — the audio-capable Gemma 4 E4B uses the `DIRECT_AUDIO` strategy — but it is the mandatory `STT_TRANSCRIBE` path for any text-only LLM and the transcript-recovery path in Chat mode. The planned compact model tier (low-RAM devices) is text-only, so Whisper performance is a prerequisite for model tiering and for usable voice on 4–6 GB devices.

## What Changes

- Enable XNNPACK in `LiteRtWhisperEngine`'s TFLite interpreter options, replacing the hardcoded `setUseXNNPACK(false)`.
- Add an int8 quantized Whisper artifact (`whisper_tiny_30s_int8.tflite`) to `model_catalog.json` and prefer it when available, keeping the `f32` artifact registered as an automatic fallback for devices/downloads where int8 is absent.
- Resolve the ASR artifact dynamically at engine initialization so the catalog entry, not a hardcoded filename, determines which artifact is used.
- Expected effect: STT latency ~1.5–3s → ~300–500ms, STT RAM footprint ~150MB → ~45MB, model download 150MB → ~45MB, with negligible WER regression.

## Capabilities

### New Capabilities

- `asr-model-fast-path`: Rules for how the ASR engine selects accelerated execution (XNNPACK) and which quantized artifact variant to load, including fallback behavior.

### Modified Capabilities

- `model-library`: The model catalog SHALL represent ASR models with multiple artifact variants (quantized and full-precision), and the system SHALL prefer the quantized variant while retaining the full-precision artifact as fallback.
- `voice-pipeline`: The default STT engine SHALL use accelerated CPU inference (XNNPACK-backed kernels) when the runtime supports it.

## Impact

- **Code**: `core/voice/stt/.../LiteRtWhisperEngine.kt` (interpreter options, artifact resolution), `core/models/src/main/assets/model_catalog.json` (int8 artifact entry), `core/models/.../ModelLibraryManager.kt` (download of new artifact), `core/ui/.../ChatViewModel.kt` (readiness check unaffected, verify only).
- **Behavior**: `VoiceInputStrategyResolver` unchanged — `STT_TRANSCRIBE` readiness now checks the preferred int8 artifact first. `DIRECT_AUDIO` strategy for audio-capable LLMs is unaffected.
- **Risks**: int8 quality regression (mitigated by f32 fallback); new artifact download flow for existing installs (mitigated by lazy download — only fetched when STT strategy is actually needed).
