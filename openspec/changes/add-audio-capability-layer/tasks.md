# Tasks: add-audio-capability-layer

## 1. Audio front-end seam (core:voice/stt)
- [ ] 1.1 Extract `AudioRecord` construction + platform-effect attach into an internal
      `AudioFrontEnd` abstraction; production impl uses real `AudioRecord`/`audiofx` API.
      Existing `customSourceReader` injection in `AudioRecorder` remains untouched.
- [ ] 1.2 Front-end resolves its configuration at construction: request
      `AudioSource.VOICE_RECOGNITION`; on non-`STATE_INITIALIZED`, retry with
      `AudioSource.MIC` and log a warning.
- [ ] 1.3 Attach `NoiseSuppressor` / `AcousticEchoCanceler` when `isAvailable()` and
      `create()` succeeds; leave `AutomaticGainControl` off by default. Release all
      attached effects in the recorder teardown path.
- [ ] 1.4 Emit the one-time observability log:
      `[Loki/AudioFrontEnd] source=<src> ns=<bool> aec=<bool> agc=<bool>`.

## 2. Tests (core:voice/stt)
- [ ] 2.1 Unit test: front-end requests `VOICE_RECOGNITION` by default (fake `AudioFrontEnd`
      records the requested source).
- [ ] 2.2 Unit test: init failure on the preferred source falls back to `MIC` without
      throwing.
- [ ] 2.3 Unit test: effects are released when the recorder is torn down (fake effect
      handles assert release called).
- [ ] 2.4 Existing `AudioRecorderTest` VAD suites still pass unchanged (front-end seam is
      transparent to VAD logic).

## 3. On-device validation & VAD recalibration
- [ ] 3.1 Device pass: confirm `VOICE_RECOGNITION` initializes on target hardware, the
      observability line reports the expected configuration, and sample-rate/duration of
      recorded buffers is unchanged (no hidden resampler).
- [ ] 3.2 RMS recalibration: run the VAD scenarios from `fix-npu-turn-context` 10.7
      (first-word onset, end-of-speech within silence window, short utterances ≥350ms kept,
      ambient bursts ignored) against the DSP pipeline; adjust absolute RMS floor constants
      if the distribution shifted; record chosen values in this change's notes.
- [ ] 3.3 STT quality check: Whisper transcripts on the DSP pipeline are at least as good
      as raw MIC for the same utterances (no DSP artifacts degrading recognition). If
      degraded, one-line revert to `MIC` + explicit effects is the documented fallback.
- [ ] 3.4 Update ROADMAP.md: mark the parked "Audio capability layer" item as delivered by
      this change (barge-in / Silero VAD remain parked).
