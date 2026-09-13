# Proposal: add-audio-capability-layer

## Why

The voice input path records via raw `MediaRecorder.AudioSource.MIC` with no platform DSP,
delivering raw transducer audio. Ambient room noise (HVAC, fans) and acoustic speaker feedback
(TTS playback leakage during multi-turn interactions) degrade audio quality for both the
DirectAudio multimodal LLM path (`Content.AudioBytes`) and the Whisper STT fallback path.
While earlier behavioral VAD fixes resolved the initial capture hangs, clean acoustic front-end
processing (hardware noise suppression, acoustic echo cancellation, and beamforming) is needed
to ensure high-fidelity audio capture and robust end-of-speech detection.

## What Changes

- **Input preset upgrade**: switch `AudioRecord` construction from `AudioSource.MIC` to
  `AudioSource.VOICE_RECOGNITION` (hardware DSP pipeline: noise suppression, beamforming,
  wind filtration where the device supports it).
- **Platform effect attach**: probe and enable `NoiseSuppressor` and `AcousticEchoCanceler`
  on the active audio session when `isAvailable()`; `AutomaticGainControl` left off by
  default (it can distort the audio encoder's expected loudness envelope).
- **Graceful fallback**: devices without VOICE_RECOGNITION support or DSP effects fall back
  to `MIC` with a logged warning; behavior never worse than today.
- **RMS recalibration awareness**: hardware DSP changes the RMS distribution, so energy
  VAD thresholds (`speechThreshold`, `silenceThreshold`, floor calibration) must be
  re-tuned on-device after this lands — the thresholds are already feel-knobs per
  earlier calibration passes.
- **Out of scope (stays parked)**: Silero on-device neural VAD, armed-mic continuous barge-in
  commit window gated on TTS state. This change is the front-end capture layer only.

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
