# Design: add-audio-capability-layer

## Context

`AudioRecorder.kt` currently constructs its `AudioRecord` with `MediaRecorder.AudioSource.MIC` (line 50), which delivers raw, unconditioned transducer audio. Platform DSP (multi-mic directional beamforming, noise suppression, and acoustic echo cancellation) is only engaged by semantics-aware sources like `VOICE_RECOGNITION` and by attaching `audiofx` effects explicitly.

While the software VAD state machine in `AudioRecorder.kt` was recently hardened in commit `78c9c3d` (using a sustained onset gate, rolling peak tracking, and a `noiseFloor * 1.6f` factor that resolved the 17.5s Turn 1 hanging bug), raw `MIC` still sends unfiltered ambient room noise and reverberation into Whisper STT and multimodal LLMs (`DIRECT_AUDIO`), causing hallucinations. Furthermore, with armed capture active during TTS playback (`isCommitGated()`), device speaker audio can bleed into the microphone without hardware `AcousticEchoCanceler` (`AEC`).

Constraints:
- `AudioRecorder` accepts a `customSourceReader` for unit tests; production uses `AndroidAudioRecordReader` which owns `AudioRecord` directly.
- Effects (`NoiseSuppressor`, `AcousticEchoCanceler`) attach per audio session ID and must be released with the recorder.
- VAD thresholds (`speechThreshold = maxOf(noiseFloor * 2.2f, 800f)`, `silenceThreshold = maxOf(recentPeak * 0.12f, 250f)`) were tuned against raw MIC; verifying their performance with a lower hardware DSP noise floor is required.

## Goals / Non-Goals

**Goals**
- Hardware directional beamforming and noise suppression on the voice capture path where the device provides it.
- Hardware acoustic echo cancellation (`AEC`) to eliminate device speaker loopback during armed capture / follow-up turns.
- Deterministic, observable front-end selection (log which source + effects are active).
- Unit testability preserved without real audio hardware.
- Graceful degradation on devices or ROMs lacking the preset or effects.

**Non-Goals**
- Silero / ML model-based VAD (remains parked on the roadmap).
- Rewriting the existing VAD commit-gating state machine.
- Redesigning STT transcription engines.

## Decisions

### D1: `VOICE_RECOGNITION` preset over `VOICE_COMMUNICATION` or explicit-effects-on-MIC
`VOICE_RECOGNITION` is the Android-recommended source for speech recognition: it requests the DSP pipeline with multi-microphone beamforming while leaving AGC off and keeping the frequency band wide. `VOICE_COMMUNICATION` bundles telephony-tuned AGC and aggressive dynamic range compression that distorts Whisper's input. Adding `NoiseSuppressor` to raw `MIC` is device-dependent and less reliable than the HAL preset's built-in multi-mic beamforming.

*Alternative considered*: explicit effects on `MIC` — retained as the fallback path (D3) for devices that reject the preset.

### D2: Attach `NoiseSuppressor` + `AcousticEchoCanceler` when `isAvailable()`, skip AGC
`NoiseSuppressor` and `AcousticEchoCanceler` attach cleanly to the `AudioRecord.audioSessionId`. AEC is critical for eliminating speaker audio bleed when the microphone is armed while TTS is completing. `AutomaticGainControl` is excluded by default: Whisper normalizes internally, and AGC pumps the background noise floor during pauses, defeating VAD floor calibration.

*Alternative considered*: AGC on to boost quiet mics — rejected for v1; Whisper and DirectAudio handle gain internally.

### D3: Capability probe + graceful fallback
At reader construction: request `VOICE_RECOGNITION`; if `AudioRecord` state is not `STATE_INITIALIZED`, retry with `MIC` and log. Each effect attaches only if `isAvailable()` and `create()` returns non-null; all attached effects are released in the teardown path.
The chosen configuration is logged once per recorder lifetime:
`[Loki/AudioFrontEnd] source=VOICE_RECOGNITION ns=true aec=true agc=false`.

*Alternative considered*: runtime toggles persisted in settings — deferred; the front-end is not user-visible behavior, only diagnosability matters now.

### D4: Injectable front-end seam for tests
Extract AudioRecord construction + effect attachment behind a narrow internal interface or factory (`AudioFrontEnd` producing the `AudioRecord` and reporting config). Production impl uses the real Android API; unit tests inject a fake or use `customSourceReader` to ensure tests remain device-independent.

### D5: VAD knob verification on hardware
Since DSP drops the ambient noise floor, this change includes an on-device verification pass on SM8750: verify onset, silence detection, and Whisper transcription with the DSP pipeline.

## Risks / Trade-offs

- [RMS scale shift affects tuned VAD thresholds] → Relative factors (`noiseFloor * 2.2f`, `noiseFloor * 1.6f`) naturally track the lower noise floor; on-device pass verifies floor behavior.
- [OEM differences in VOICE_RECOGNITION] → D3 fallback ensures devices that reject the preset gracefully revert to raw `MIC`.
- [Effects attach latency] → Negligible (<5ms during recorder start); measured in the device pass.
- [Whisper quality regression from aggressive DSP] → On-device check confirms Whisper word error rate is improved or unchanged.

## Migration Plan

Single-module change in `core/voice/stt`. No config migration. Rollback = revert the preset constant and effect attach in `AndroidAudioRecordReader`.

## Open Questions

- Does any target device route `VOICE_RECOGNITION` through a resampler that changes the effective sample rate from the requested 16 kHz? Verify with the recorded-buffer duration check in the device pass.
- Should the front-end config be surfaced in the assistant's debug/observability UI, or is logcat sufficient? (Default: logcat only.)

