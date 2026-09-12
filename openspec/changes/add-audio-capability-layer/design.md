# Design: add-audio-capability-layer

## Context

`AudioRecorder.kt` builds its `AudioRecord` with `MediaRecorder.AudioSource.MIC` (line 50),
which delivers raw transducer audio on most devices. Platform DSP (noise suppression,
beamforming, AEC) is only engaged by semantics-aware sources like `VOICE_RECOGNITION` and
`VOICE_COMMUNICATION`, or by attaching `audiofx` effects explicitly. The energy VAD in the
same file operates on this raw signal, and ambient room energy (600–1200 RMS) is easily
mistaken for speech continuation — observed as multi-second capture hangs.

Constraints:
- `AudioRecorder` accepts a `customSourceReader` for tests; production uses
  `AndroidAudioRecordReader` which owns `AudioRecord` directly.
- Effects (`NoiseSuppressor`, `AcousticEchoCanceler`) attach per audio session id and must
  be released with the recorder.
- VAD thresholds were tuned on-device against raw MIC RMS values
  (`fix-npu-turn-context` sections 10.x); a DSP source changes the RMS distribution.

## Goals / Non-Goals

**Goals**
- Hardware noise suppression on the voice capture path where the device provides it.
- Deterministic, observable front-end selection (log which source + effects are active).
- Testability preserved without real audio hardware.
- Graceful degradation on devices lacking the preset or effects.

**Non-Goals**
- Silero / model-based VAD (parked in ROADMAP).
- Barge-in / armed-mic during TTS playback (parked in ROADMAP).
- Retuning VAD algorithms — only recalibrating knob defaults on-device.

## Decisions

### D1: `VOICE_RECOGNITION` preset over `VOICE_COMMUNICATION` or explicit-effects-on-MIC
`VOICE_RECOGNITION` is the Android-recommended source for speech-to-text: it requests the
DSP pipeline while leaving AGC off and keeping the signal band wide. `VOICE_COMMUNICATION`
bundles AEC/AGC tuned for telephony (narrow-band flavor, aggressive AGC) which distorts
Whisper input. Adding `NoiseSuppressor` to raw MIC is device-dependent and less reliable
than the preset's built-in routing.

*Alternative considered*: explicit effects on `MIC` — kept as the fallback path (D3) for
devices that reject the preset.

### D2: Attach `NoiseSuppressor` + `AcousticEchoCanceler` when `isAvailable()`, skip AGC
The effects are idempotent-safe to attach and cheap. `AutomaticGainControl` is excluded by
default: Whisper normalizes internally and AGC pumps the noise floor, which directly
attacks the VAD's floor calibration. A debug-config flag can enable it later if devices
prove too quiet.

*Alternative considered*: AGC on to fight quiet mics — rejected for v1; revisit with device
logs if absolute RMS runs too low post-DSP.

### D3: Capability probe + graceful fallback
At reader construction: request `VOICE_RECOGNITION`; if `AudioRecord` state is not
`STATE_INITIALIZED`, retry with `MIC` and log. Each effect attaches only if
`isAvailable()` and `create()` returns non-null; release in the existing cleanup path.
The chosen configuration is logged once per recorder lifetime:
`[Loki/AudioFrontEnd] source=VOICE_RECOGNITION ns=true aec=false agc=false`.

*Alternative considered*: runtime toggles persisted in settings — deferred; the front-end
is not user-visible behavior, only diagnosability matters now.

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
- [Whisper quality regression from DSP artifacts] → compare STT transcripts on-device
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
