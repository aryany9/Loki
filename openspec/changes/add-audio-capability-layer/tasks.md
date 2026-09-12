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

## 3. On-device validation & VAD verification
- [ ] 3.1 Device pass: confirm `VOICE_RECOGNITION` initializes on target hardware, the
      observability line reports the expected configuration, and sample-rate/duration of
      recorded buffers is unchanged (no hidden resampler).
- [ ] 3.2 VAD verification: verify existing VAD thresholds (sustained onset ≥250ms,
      `speechThreshold = maxOf(noiseFloor * 2.2f, 800f)`, `1.6f` continuation factor,
      short utterances ≥350ms kept, ambient bursts ignored) against the DSP pipeline;
      adjust floor constants only if the distribution shifted on device.
- [ ] 3.3 Acoustic & AEC check: verify `AcousticEchoCanceler` suppresses TTS speaker bleed
      during armed follow-up capture, and verify Whisper transcripts / DirectAudio on the
      DSP pipeline show reduced background noise hallucinations. If degraded, one-line
      revert to `MIC` is the documented fallback.
- [ ] 3.4 Update ROADMAP.md: mark the "Audio capability layer" item as delivered by this
      change (Silero VAD remains parked).

