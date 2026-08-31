# voice-start-cue — Tasks

## voice-start-cue

- [ ] **T1 — Audio cue helper (D1)**
  - Create `core/sound/AudioCue.kt` exposing `playStartTone(context)` (kotlin) that synthesizes a ~250 ms 440→880 Hz glide via `AudioTrack` on a background thread, using `AudioAttributes` (`USAGE_ASSISTANCE_ACCESSIBILITY`, `CONTENT_TYPE_SONIFICATION`, `STREAM_MUSIC`).
  - Add guard flag + idempotent single-shot playback per instance.
  - **Verification:** local unit test: `playStartTone` calls AudioTrack once and is idempotent on repeated calls in the same session.

- [ ] **T2 — Trigger from chat mic (D3)**
  - In `ChatViewModel.startVoiceInput()` (or composer mic callback), call `AudioCue.playStartTone()` before the recorder/STT path opens. Reset guard on voice-stop.
  - **Verification:** press mic → tone plays before recording begins; pressing stop does not play tone.

- [ ] **T3 — Trigger from assistant long-press (D3)**
  - In `LokiVoiceInteractionSession` voice-start path, call `AudioCue.playStartTone()` at long-press threshold, before the listening overlay animates in. Reset guard on voice end.
  - **Verification:** long-press assistant → tone plays on start, not on start→stop cycle; survives config change (guard prevents doubles).

- [ ] **T4 — Flag / migration gate (D4 migration)**
  - (Optional) Add a hidden `audioStartCueEnabled` flag defaulting to `true`; keep behind `core/sound` module so it can be disabled for accessibility.
  - **Verification:** with flag false, no tone plays on either entry point.