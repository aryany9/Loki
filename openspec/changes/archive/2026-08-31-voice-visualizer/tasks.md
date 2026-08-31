## 1. Amplitude wiring (data source)

- [x] 1.1 Confirm `AudioRecorder.recordUtterance(onRmsUpdate)` already emits per-buffer RMS; add a new `SttEvent.Amplitude(rms: Float)` to `SttEngine.kt`
- [x] 1.2 In `LiteRtWhisperEngine.startListening()`, pass `onRmsUpdate` through and emit `SttEvent.Amplitude`; in `AssistantSession`'s STT collect, feed it into the session amplitude StateFlow
- [x] 1.3 In `AssistantSession.executeDirectAudioTurn`, pass an `onRmsUpdate` to `recordUtterance` feeding the same amplitude StateFlow
- [x] 1.4 Add `amplitude: StateFlow<Float>` (0..1) to `AssistantSession`, normalized (coerced ceiling) + one-pole smoothed, throttled ~30fps (conflate ~33ms)
- [x] 1.5 Unit test: amplitude smoothing/normalization produces values in 0..1 and is monotonic-ish under a test RMS stream; STT path emits Amplitude events

## 2. Equalizer composable

- [x] 2.1 Add `VoiceEqualizer(amplitude: Float, mode)` in `LokiVoiceInteractionSession.kt` using Compose `Canvas`: ~7 bars, heights from amplitude + per-bar phase, `MaterialTheme.colorScheme.primary`
- [x] 2.2 State modes: Listening (reactive), Processing (gentle pulse), Speaking (slow sine wave), Idle (static low); animate with `animateFloatAsState`
- [x] 2.3 Place equalizer in `VoiceSessionOverlay` above state text; bind `AssistantState` → mode

## 3. Vector icon cleanup (overlay)

- [x] 3.1 Replace 🎙️ with `Icons.Default.Mic`, 🔊 with `Icons.AutoMirrored.Filled.VolumeUp` (or VolumeUp), ⚠️ with `Icons.Default.Warning` in the overlay state rows
- [x] 3.2 Grep confirms no unicode glyphs remain in `LokiVoiceInteractionSession.kt`

## 4. Validation

- [x] 4.1 `./gradlew test :app:assembleDebug` passes
- [x] 4.2 Manual on Samsung device: lock-screen voice → equalizer animates with speech during DIRECT_AUDIO listening; text-only/STT path animates during capture; Processing pulses; Speaking waves; Idle static; bars settle low in silence
- [x] 4.3 Manual: aesthetic check — bar count/ceiling tuned so speech is clearly visible without clipping; dark/light/Dynamic Color themes respected
- [x] 4.4 Run `openspec validate voice-visualizer` and confirm all tasks complete