# voice-start-cue
Play a short, non-speech audio cue (Gemini-like rising attention tone) when voice input begins — for the chat mic and the assistant long-press — so the user knows recording/listening has started.

## Why

The voice interaction currently gives only visual feedback when voice starts. New users have no confirmation that speaking has begun until they see an LLM turn. A short audio cue (like Gemini) makes the start-of-speech moment explicit and unambiguous.

## What Changes

- Add a short (≈200–300 ms) non-TTS attention tone as either a bundled raw asset or runtime-synthesized via `AudioTrack` (decide in design review — prefer runtime synthesis to avoid a new binary dependency, but a 2 KB WAV `res/raw/attention_start.wav` is acceptable).
- Play the cue at the start of the voice path, BEFORE mic opens / listening state begins.
- Two trigger points:
  1. Chat composer mic (`ChatViewModel.startVoiceInput` / composer) — also covers the whisper/ASR chat voice flow.
  2. Assistant long-press (`LokiVoiceInteractionSession` voice overlay) — starts on press, NOT on release.
- Do NOT play on stop/completion; do NOT double-play on config change.

## Capabilities

### New Capabilities
- `audio-start-cue`: system SHALL play a short non-speech attention tone at the beginning of any voice-input session, regardless of the selected STT/model path (chat mic or assistant).

### Modified Capabilities
- `voice-interaction-ui`: ADDITION — play start tone on voice session begin (behavior + state unchanged otherwise). Existing visualization/equalizer behavior unchanged.
- `chat-ui`: chat mic press gains a start tone; composer mic/stop morphing behavior unchanged.

## Impact

- **Code:** new tiny `SoundPlayer`/`AudioCue` helper in `core/sound` (or `core/ui`); call sites in `ChatViewModel` + a path reached by `LokiVoiceInteractionSession`.
- **Assets:** 1 small raw asset OR a few lines of `AudioTrack` generation.
- **Dependencies:** none (uses framework `AudioTrack`/`MediaPlayer`).
- **Specs:** `voice-interaction-ui` delta; `chat-ui` delta (mic flow notes the tone).
- **Risk:** low. Audio on the wrong thread, doubled playback, or cue playing on stop. Gated by playback flag + idempotent trigger.

## Out of Scope

- Voice stop/success/error tones (potential later; separate change).
- Ringtones/notification sounds for background assistant events.
- STT engine changes / whisper provisioning (unchanged).