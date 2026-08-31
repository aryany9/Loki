# voice-visualizer

## Why

The voice overlay only shows static text ("Listening…"), giving no feedback that the mic is actually live or that speech is being captured. Gemini-style assistants show a reactive equalizer so users know the assistant is listening and how loudly. The data already exists: `AudioRecorder.recordUtterance()` has an unused `onRmsUpdate` RMS callback (built for VAD) that both voice strategies funnel through.

## What Changes

- Expose the existing per-buffer RMS amplitude from `AudioRecorder` (already computed for VAD) through `recordUtterance(onRmsUpdate)` and, for the STT path, as a new `SttEvent.Amplitude` event from `LiteRtWhisperEngine.startListening()`.
- `AssistantSession` publishes a smoothed, normalized `amplitude: StateFlow<Float>` (0..1) while Listening.
- `VoiceSessionOverlay` renders a Gemini-style bar equalizer via Compose `Canvas`: reactive vertical bars during Listening (driven by smoothed RMS), gentle pulse during Processing, soft wave during Speaking, static bars when Idle.
- Replace remaining unicode glyphs in the overlay (🎙️ 🔊 ⚠️) with Material vector icons per the `ui-design-tokens` rule.
- One visualizer for both strategies (DIRECT_AUDIO and STT_TRANSCRIBE) — single recorder, single hook.
- Out of scope: true FFT per-frequency-band spectrum (RMS-driven bars with per-bar smoothing achieve the Gemini look without DSP overhead), TTS playback amplitude (Speaking is synthetically animated), chat-screen visualizer.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `voice-interaction-ui`: Listening state SHALL render a live amplitude-driven equalizer visualization; state visuals upgraded; vector icons.

## Impact

- **Code**: `AudioRecorder.kt` (no change — hook exists), `LiteRtWhisperEngine.kt` (pass `onRmsUpdate` through / emit `SttEvent.Amplitude`), `AssistantSession.kt` (amplitude StateFlow), `LokiVoiceInteractionSession.kt` (equalizer composable, icons), `SttEngine.kt` (new event type).
- **Dependencies**: none — pure Compose `Canvas`.
- **Specs**: `voice-interaction-ui` delta.
- **Risk**: recomposition rate (throttle amplitude updates to ~30fps via conflation); two-mic conflict (visualizer must use the active recorder's stream — design guarantees single source).
