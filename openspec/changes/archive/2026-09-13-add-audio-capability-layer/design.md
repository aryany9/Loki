# Design: add-audio-capability-layer

## Context

`AudioRecorder.kt` builds its `AudioRecord` with `MediaRecorder.AudioSource.MIC` (line 50),
which delivers raw transducer audio on most devices. Platform DSP (noise suppression,
beamforming, AEC) is only engaged by semantics-aware sources like `VOICE_RECOGNITION` and
`VOICE_COMMUNICATION`, or by attaching `audiofx` effects explicitly.
The energy VAD in the same file operates on this raw signal. While earlier behavioral tuning
mitigated initial capture hangs, ambient room noise (HVAC, fans) and speaker feedback
(TTS playback leakage during multi-turn interactions) degrade signal quality for both DirectAudio
multimodal inference (`Content.AudioBytes`) and Whisper STT fallback.

Constraints:
- `AudioRecorder` accepts a `customSourceReader` for tests; production uses
  `AndroidAudioRecordReader` which owns `AudioRecord` directly.
- Effects (`NoiseSuppressor`, `AcousticEchoCanceler`) attach per audio session id and must
  be released with the recorder.
- VAD thresholds were tuned on-device against raw MIC RMS values; a DSP source changes the RMS distribution.

## Goals / Non-Goals

**Goals**
- Hardware noise suppression and acoustic echo cancellation on the voice capture path where the device provides it.
- Deterministic, observable front-end selection (log which source + effects are active).
- Testability preserved without real audio hardware.
- Graceful degradation on devices lacking the preset or effects.

**Non-Goals**
- Silero / model-based VAD (parked in ROADMAP).
- Continuous barge-in during TTS playback (parked in ROADMAP).
- Retuning VAD algorithms — only recalibrating knob defaults on-device.

## Decisions

### D1: `VOICE_RECOGNITION` preset with robust `MIC` fallback
`VOICE_RECOGNITION` is the Android-recommended source for speech input: it requests the
DSP pipeline while leaving AGC off and keeping the signal band wide. `VOICE_COMMUNICATION`
bundles AEC/AGC tuned for telephony (narrow-band flavor, aggressive AGC) which distorts
both direct-audio LLM embeddings and Whisper input. 

If `VOICE_RECOGNITION` fails to initialize (device quirk), the system must explicitly attempt to attach `NoiseSuppressor` and `AcousticEchoCanceler` to the fallback `MIC` session.

### D2: Side-Chain Filtering & Multi-Stage VAD
To prevent destruction of low-frequency mel-spectrograms required by multimodal LLMs, filtering MUST be applied via a **side-chain**, keeping the primary audio feed pure.

*   **Primary Feed**: Unfiltered, raw PCM bytes routed through a 300ms (4800 samples at 16kHz) ring buffer to prevent clipping the leading edges of soft consonants prior to onset confirmation.
*   **Side-Chain (Stage 1: Physical Rejection)**: A 130 Hz High-Pass IIR filter whose instance is persisted across the entire `AudioRecord` lifecycle (preventing phase jump artifacts). The filtered signal feeds into a ZCR boundary gate (`<= 2` discard, `3-16` pass, `>= 25` pass, `> 30 + crest > 7` discard) and an RMS envelope gate to reject chassis handling noise and non-vocal sounds.
*   **Stage 2 (Semantic Gate)**: Once Stage 1 passes, the audio is analyzed by a Micro-VAD (Silero/WebRTC) to definitively confirm human speech.
*   **Stage 3 (Wake LLM)**: Upon VAD hysteresis met, the ring buffer is flushed to the LLM (DirectAudio stream).

### D3: Capability probe + graceful fallback
At reader construction: request `VOICE_RECOGNITION`; if `AudioRecord` state is not
`STATE_INITIALIZED`, retry with `MIC` and attempt explicit DSP effect attachment. Each effect attaches only if
`isAvailable()` and `create()` returns non-null; release in the existing cleanup path using strict `finally` block teardowns to prevent native memory leaks.
The chosen configuration is logged once per recorder lifetime.

### D4: Injectable front-end seam for tests
Extract AudioRecord construction + effect attachment behind a narrow internal interface
(e.g. `AudioFrontEnd` producing the `AudioRecord` and reporting config). Production impl
uses the real API; unit tests inject a fake that records which source/effects were
requested. Existing `customSourceReader` injection stays untouched.

### D5: VAD knob recalibration happens in this change, on-device only
Since DSP shifts the RMS distribution, this change owns one tuning pass (mirroring
`fix-npu-turn-context` 10.7): verify onset/silence behavior with the DSP pipeline and
adjust the absolute floors (`800f`/`600f`-style constants) if needed. Relative factors
(`noiseFloor * N`) should mostly survive.

## Risks / Trade-offs

- [RMS scale shift breaks tuned VAD thresholds] → D5 on-device tuning pass; knobs are
  centralized; log line from D3 makes it obvious which front-end produced a given log.
- [Some OEMs ignore VOICE_RECOGNITION DSP promises] → D3 fallback + observability; behavior
  never worse than raw MIC.
- [Effects attach changes latency of recorder start] → negligible (per-session attach);
  measure in the device pass.
- [Audio quality regression from DSP artifacts] → compare DirectAudio comprehension and Whisper transcripts on-device
  before/after in the tuning task; preset choice is a one-line revert.

## Migration Plan

Single-module change in `core/voice/stt`. No config migration. Rollback = revert the
preset constant and effect attach (contained in `AudioFrontEnd` impl).

## Open Questions

- Does any target device route `VOICE_RECOGNITION` through a resampler that changes the
  effective sample rate from the requested 16 kHz? Verify with the recorded-buffer
  duration check in the device pass.
- Should the front-end config be surfaced in the assistant's debug/observability UI, or is
  logcat sufficient? (Default: logcat only.)
