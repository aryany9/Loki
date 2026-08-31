# voice-start-cue — Design

## Context

Voice input has two entry points: the **chat mic** (in the composer / `ChatViewModel.startVoiceInput`) and the **assistant long-press** (voice overlay `LokiVoiceInteractionSession`). Both currently transition directly to a listening/record state with only visual feedback (equalizer / "listening" text). There is no audio cue. Gemini (the reference) plays a short attention tone when voice begins — not TTS, no words.

## Goals / Non-Goals

**Goals:** a short (~250 ms), Gemini-style rising "ding" plays exactly once when a voice session begins, on both entry points, before recording/listening; never plays on stop or config change; respects device audio stream/DND.

**Non-Goals:** stop/success/error tones; STT provisioning; equalizer changes; notification/background sounds.

## Decisions

### D1: Runtime-synthesized tone via AudioTrack (no bundled asset)
Implement a tiny `AudioCue.playStartTone()` in a new `core/sound` module that synthesizes a 440 Hz -> 880 Hz frequency glide over ~250 ms with a fast attack / short release (a soft "ding"), writing the samples to an `AudioTrack` (STREAM_MUSIC) on a background thread. No new binary asset → keeps the repo asset-free and trivially themeable/mute-able.
*Rationale:* avoids asset review/threshold bloat; framework-only; easy to disable for accessibility. Rejected: bundled WAV — adds a binary asset and an `AssetFileDescriptor` lifecycle dependency; acceptable fallback if synthesis proves unreliable on some devices.

### D2: Idempotent trigger with playback guard
Gate playback behind a per-session boolean (`voiceStartCuePlayed`). Each `startVoiceSession()` resets it to `false`; the first call to the cue within a session plays it, subsequent calls are no-ops. Release (stop) sets it back to `false` so the next session plays again. This prevents double-play on config changes and on repeated internal start notifications.
*Rationale:* robust against Compose recomposition restarts and ViewModel rebinds.

### D3: Trigger placement
- **Chat mic:** in `ChatViewModel.startVoiceInput()`, immediately before/after opening the recorder — play cue, THEN begin recording. (If whisper/ASR is async, cue plays on the synchronous "begin" call.)
- **Assistant long-press:** in `LokiVoiceInteractionSession.onStartVoiceInput()` (or equivalent press handler) — cue plays when the long-press threshold fires, before the listening overlay animates in.
*Rationale:* cue = "you may start speaking now"; must precede the actual audio capture to be accurate.

### D4: Audio attributes & stream
Use `AudioManager.STREAM_MUSIC` with `AudioAttributes` `USAGE_ASSISTANCE_ACCESSIBILITY` + `CONTENT_TYPE_SONIFICATION` so the cue respects media volume and is ducked appropriately, and is suppressed by DND when appropriate. (Compare with equalizer which already uses an appropriate stream.)
*Rationale:* consistent, low-surfaced-permission audio that does not compete with TTS.

## Risks / Trade-offs

- [Cue plays twice on config change] → mitigated by D2 guard; covered by T3.
- [Cue plays on stop instead of start] → trigger only in the START path; unit-test the call sites.
- [AudioTrack underrun / wrong sample rate] → use `AudioTrack.getNativeOutputSampleRate` and 44100 fallback; log on failure, never block input.
- [Accessibility / hearing sensitivity] → short & soft; revisit as stop/success tones if requested (separate change).
- [Battery / thread] → <50 ms of playback on a single-shot background dispatch; negligible.

## Migration Plan

Additive: new `core/sound` module + two call sites + one-line reset in each voice-stop path. No data/state migration. Rollback = disable flag (default off behind `audioStartCueEnabled`) for a safe kill switch, or revert.

## Open Questions

- Ship tone: 250 ms glide (D1 default) vs. a custom brand tone — resolved as glide for v1.
- Accessibility toggle: expose as a "voice feedback" preference — deferred; add a hidden flag `audioStartCueEnabled` (default true) for now and file the pref as a follow-up.