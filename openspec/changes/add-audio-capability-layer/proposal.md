# Proposal: add-audio-capability-layer

## Why

The voice input path records via raw `MediaRecorder.AudioSource.MIC` with no platform DSP,
so ambient room noise (HVAC, fans, speech-level fluctuations at 600–1200 RMS) repeatedly
defeats the energy VAD — observed as a 17.5s capture hang on Turn 1 and inconsistent
end-of-speech between turns. The roadmap parked this audio front-end redesign as a separate
change so the behavioral VAD fixes could ship first; that prerequisite work is now in
`fix-npu-turn-context`.

## What Changes

- **Input preset upgrade**: switch `AudioRecord` construction from `AudioSource.MIC` to
  `AudioSource.VOICE_RECOGNITION` (hardware DSP pipeline: noise suppression, beamforming,
  wind filtration where the device supports it).
- **Platform effect attach**: probe and enable `NoiseSuppressor` and `AcousticEchoCanceler`
  on the active audio session when `isAvailable()`; `AutomaticGainControl` left off by
  default (it can distort Whisper's expected loudness envelope).
- **Graceful fallback**: devices without VOICE_RECOGNITION support or DSP effects fall back
  to `MIC` with a logged warning; behavior never worse than today.
- **RMS recalibration awareness**: hardware DSP changes the RMS distribution, so all energy
  VAD thresholds (`speechThreshold`, `silenceThreshold`, floor calibration) must be
  re-tuned on-device after this lands — the thresholds are already feel-knobs per
  `fix-npu-turn-context` 10.7.
- **Out of scope (stays parked)**: Silero on-device VAD, armed-mic barge-in commit window
  gated on TTS state. This change is the front-end only; the roadmap's full audio-stack
  redesign follows separately.

## Capabilities

### New Capabilities
- `audio-input-front-end`: runtime selection of the audio input preset and platform DSP
  effects for voice capture, with capability probing, graceful fallback, and observability
  of the chosen front-end configuration.

### Modified Capabilities
- `voice-pipeline`: voice capture must request a DSP-backed input source rather than raw
  mic when available, and expose which front-end is active for diagnosability.

## Impact

- `core/voice/stt/AudioRecorder.kt` (`AndroidAudioRecordReader` — AudioRecord construction,
  effect attach/release lifecycle).
- Unit tests fake the reader, so presets/effects need a seam (`AudioFrontEndFactory` or
  injectable source) to keep tests device-independent.
- No public API changes; no data migration. Risk: RMS scale shift invalidates tuned VAD
  knobs — mitigated by the re-tuning task and knob defaults living in one place.
