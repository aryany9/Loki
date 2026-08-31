# voice-visualizer — Design

## Context

`AudioRecorder.recordUtterance(onRmsUpdate: ((Float) -> Unit)? = null)` already computes per-buffer RMS and exposes an unused callback — the exact data needed for a live visualizer. Both voice strategies (DIRECT_AUDIO via `AssistantSession.executeDirectAudioTurn`, STT_TRANSCRIBE via `LiteRtWhisperEngine.startListening()`) call `recorder.recordUtterance()`, so a single hook feeds visualizer for both. `VoiceSessionOverlay` currently shows static text + unicode glyphs (🎙️🔊⚠️). It lives in `core/assistant` (theme available since the `core/theme` extraction).

## Goals / Non-Goals

**Goals:** Gemini-style reactive equalizer during Listening; smooth, throttled animation; vector icons; one visualizer for both strategies; no new dependencies.

**Non-Goals:** FFT frequency spectrum (RMS-driven bars are cheaper and achieve the look), TTS output amplitude (Speaking is synthetically pulsed), chat-screen visualizer, on-device audio pre/post-processing.

## Decisions

### D1: RMS via existing hook — one amplitude source for both strategies
- DIRECT_AUDIO: `executeDirectAudioTurn` already calls `recorder.recordUtterance()`; pass an `onRmsUpdate` that publishes to `AssistantSession.amplitude`.
- STT_TRANSCRIBE: `LiteRtWhisperEngine.startListening()` calls `recorder.recordUtterance()`; add a new `SttEvent.Amplitude(rms: Float)` emitted from the callback; `AssistantSession` collects it into `amplitude`.
Both paths write to the same `MutableStateFlow<Float> amplitude` in `AssistantSession`, exposed as `StateFlow<Float>`. Ensures the visualizer reads one LiveData-like source and never two mics at once (each strategy opens its own recorder via the same `AudioRecorder`).

### D2: Normalization + smoothing in the session, throttle to ~30fps
RMS is a raw energy value; normalize to 0..1 via `coerceIn` against a configurable ceiling (e.g. `sqrt(2)`-scaled MaxBuffer) and apply a one-pole low-pass for smooth rise/decay. Throttle writes (conflate ~33ms) so recomposition isn't per-audio-buffer (buffers are 100ms but MaxBuffer/visual needs finer steps). Keep the transition fast so the bars feel live, not laggy.

### D3: Visualizer composable — Compose `Canvas` bar equalizer
`VoiceEqualizer(amplitude: Float, state)` draws N bars (e.g. 7) with heights as a function of `amplitude` + per-bar phase offset, using `MaterialTheme.colorScheme.primary`. State behavior:
- Listening → reactive bars scale with live `amplitude`
- Processing → bars pulse at a fixed small amplitude (subtle breathing)
- Speaking → bars wave slowly (synthetic sine pulse)
- Idle → flat static bars at low height
Animated with `animateFloatAsState` so bar movement is smooth. `Canvas` color from theme tokens.

### D4: State-driven wrapper
`VoiceSessionOverlay` places the equalizer above the state text. It binds `AssistantState` → equalizer mode (Listening/Processing/Speaking/Idle). Keeps the existing transcript text; the equalizer is additive, replacing nothing semantically.

### D5: Vector icons cleanup in overlay
Replace 🎙️ (Mic icon), 🔊 (VolumeUp), ⚠️ (Warning) with `material-icons-extended` vector icons (already a dependency) in the overlay's state rows — satisfies the existing `ui-design-tokens`/`modernize-home-motion` glyph ban.

## Risks / Trade-offs

- [Recomposition at high amplitude-update frequency] → throttle to ~30fps (D2); `Canvas` is lightweight.
- [Two-mic conflict if both strategies somehow record] → single `amplitude` StateFlow + each strategy owns its recorder; state machine ensures only one Listening at a time.
- [Amplitude ceiling tuning] → normalization ceiling is a constant; calibrate against real speech on the Samsung test device (validated in tasks).
- [STT path amplitude latency] → STT already buffers audio; amplitude emitted live from the same callback, no added latency.

## Migration Plan

Additive: new event + StateFlow + composable. No API breaks (hook already existed). Rollback = revert.

## Open Questions

None blocking. Bar count and animation feel calibrated empirically on-device.