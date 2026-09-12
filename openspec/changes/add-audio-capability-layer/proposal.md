# Proposal: add-audio-capability-layer

## Why

While the earlier 17.5-second capture hang was successfully resolved in software via the upgraded energy VAD state machine (sustained onset gating, rolling peak tracking, and 1.6x noise-floor isolation in `AudioRecorder.kt`), the underlying audio capture path remains on raw `MediaRecorder.AudioSource.MIC`.

Raw transducer capture presents three critical limitations on modern hardware:
1. **Whisper STT & DirectAudio Degradation**: Unfiltered ambient room noise (HVAC, fans, typing, background conversations) enters the PCM buffer, increasing Whisper word error rates and provoking hallucinations (spurious subtitle tags like `[Music]` or repeated words).
2. **Missing Multi-Mic Spatial Beamforming**: Modern smartphones (e.g., Samsung Galaxy S25 / Pixel) have multi-microphone arrays. Raw `MIC` records omnidirectionally, whereas `AudioSource.VOICE_RECOGNITION` instructs the Android HAL to form a directional beam toward the user's mouth, rejecting off-axis environmental noise.
3. **TTS Speaker Bleed on Armed Mic**: Now that armed-mic commit gating is active during TTS playback (`isCommitGated()` in `AudioRecorder.kt`), device speaker output can leak directly into the microphone. Without platform `AcousticEchoCanceler` (AEC), the assistant risks transcribing its own synthesized voice or false-triggering follow-up turns.

Upgrading the capture front-end to hardware-conditioned audio resolves these issues at the driver/DSP level.

## What Changes

- **Input preset upgrade**: Switch default `AudioRecord` construction from `AudioSource.MIC` to `AudioSource.VOICE_RECOGNITION` (activating HAL-level multi-mic beamforming, wind filtering, and noise conditioning).
- **Platform effect attach**: Probe and attach `NoiseSuppressor` and `AcousticEchoCanceler` (`AEC`) to the active audio session when `isAvailable()`; leave `AutomaticGainControl` (`AGC`) off by default so as not to pump the background noise floor for Whisper or VAD.
- **Graceful fallback**: If `VOICE_RECOGNITION` or effect creation fails on a device/ROM, cleanly fall back to `MIC` with a diagnostic log; capture behavior never degrades below current functionality.
- **VAD verification**: Verify that current VAD threshold calculations (`speechThreshold = maxOf(noiseFloor * 2.2f, 800f)`, `1.6f` factor, `250ms` sustained onset) operate cleanly on the lowered noise floor of the DSP pipeline.
- **Out of scope (stays parked)**: Silero model-based VAD (remains parked on the roadmap). Full audio-stack refactoring follows separately.

## Capabilities

### New Capabilities
- `audio-input-front-end`: Runtime selection of the audio input preset (`VOICE_RECOGNITION` with `MIC` fallback) and platform DSP effects (`NoiseSuppressor`, `AcousticEchoCanceler`), with capability probing, graceful degradation, and observability logging.

### Modified Capabilities
- `voice-pipeline`: Voice capture requests a DSP-backed directional input source with acoustic echo cancellation rather than raw omnidirectional mic when supported.

## Impact

- `core/voice/stt/AudioRecorder.kt`: `AndroidAudioRecordReader` constructs `AudioRecord` with `VOICE_RECOGNITION`, manages effect lifecycle (attach on start, release on teardown), and falls back to `MIC` on init failure.
- `core/voice/stt/AudioRecorderTest.kt`: Adds unit tests for the front-end reader seam and verifies that all 15+ existing VAD scenario suites pass unchanged.
- No public API changes; no database or settings schema migrations.

