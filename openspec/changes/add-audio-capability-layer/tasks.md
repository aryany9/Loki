# Tasks: add-audio-capability-layer

## 1. Audio front-end seam (core:voice/stt)
- [x] 1.1 Extract `AudioRecord` construction + platform-effect attach into an internal
      `AudioFrontEnd` abstraction; production impl uses real `AudioRecord`/`audiofx` API.
      Existing `customSourceReader` injection in `AudioRecorder` remains untouched.
- [x] 1.2 Front-end resolves its configuration at construction: request
      `AudioSource.VOICE_RECOGNITION`; on non-`STATE_INITIALIZED`, retry with
      `AudioSource.MIC` and log a warning.
- [x] 1.3 Attach `NoiseSuppressor` / `AcousticEchoCanceler` when `isAvailable()` and
      `create()` succeeds; leave `AutomaticGainControl` off by default. Release all
      attached effects in the recorder teardown path.
- [x] 1.4 Emit the one-time observability log:
      `[Loki/AudioFrontEnd] source=<src> ns=<bool> aec=<bool> agc=<bool>`.

## 2. Tests (core:voice/stt)
- [x] 2.1 Unit test: front-end requests `VOICE_RECOGNITION` by default (fake `AudioFrontEnd`
      records the requested source).
- [x] 2.2 Unit test: init failure on the preferred source falls back to `MIC` without
      throwing.
- [x] 2.3 Unit test: effects are released when the recorder is torn down (fake effect
      handles assert release called).
- [x] 2.4 Existing `AudioRecorderTest` VAD suites still pass unchanged (front-end seam is
      transparent to VAD logic).

## 3. On-device validation & VAD recalibration
- [x] 3.1 Device pass: confirm `VOICE_RECOGNITION` initializes on target hardware, the
      observability line reports the expected configuration, and sample-rate/duration of
      recorded buffers is unchanged (no hidden resampler).
- [x] 3.2 RMS recalibration: run the VAD scenarios from `fix-npu-turn-context` 10.7
      (first-word onset, end-of-speech within silence window, short utterances ≥350ms kept,
      ambient bursts ignored) against the DSP pipeline; adjust absolute RMS floor constants
      if the distribution shifted; record chosen values in this change's notes.
- [x] 3.3 Audio quality check: DirectAudio multimodal comprehension and Whisper transcripts on
      the DSP pipeline are at least as good as raw MIC for the same utterances (no DSP
      artifacts degrading recognition). If degraded, one-line revert to `MIC` + explicit
      effects is the documented fallback.
- [x] 3.4 Update ROADMAP.md: mark the parked "Audio capability layer" item as delivered by
      this change (barge-in / Silero VAD remain parked).

## 4. DirectAudio Stability Guardrails (core:llm / core:voice)
- [x] 4.1 Audio Token Estimation (`LiteRtLlmEngine`): Update `turnEstTokens` to intercept `Content.AudioBytes`. Implement the acoustic token math using a hardware-specific compression constant (25 tokens per second for Gemma 4). Formula: `durationSeconds = bytes.size / (16000 * 2)`, `audioTokens = durationSeconds * 25`.
- [x] 4.2 VAD Circuit Breaker (`AudioRecorder`): Inject a capacity threshold check into the recording loop. If `Current_History_Tokens + Prompt_Tokens + Running_Audio_Tokens >= (MAX_KV_CACHE - RESERVED_GENERATION_TOKENS)`, forcefully trip the VAD sequence, stop recording, and invoke `onSpeechEnded` to trigger inference before the KV-cache overflows.

## 5. Async Whisper Transcript Recovery (UX & History)
- [x] 5.1 Room State Machine & UI Stream: Update `ChatMessageEntity` with `TranscriptStatus` enum (`PENDING`, `COMPLETED`, `FAILED`). Modify the UI layer to render a shimmer effect on `PENDING` audio bubbles, smoothly replacing it with the transcribed text when the Room `Flow` emits `COMPLETED`.
- [x] 5.2 CPU Contention Mitigation: Restrict `LiteRtWhisperEngine` to a background dispatcher (`THREAD_PRIORITY_BACKGROUND`) with a maximum of 2 threads to prevent starvation of the TTS `AudioTrack` and Gemma NPU/GPU delegates.
- [x] 5.3 OOM Circuit Breaker: Implement `ComponentCallbacks2` in the inference coordinator. On `TRIM_MEMORY_RUNNING_CRITICAL`, release the Whisper engine handles to protect the Gemma inference session, leaving pending transcripts in the `FAILED` state. Ensure Whisper uses `FileChannel.MapMode.READ_ONLY` for its 150MB asset mapping.
- [x] 5.4 KV-Cache Isolation: Ensure the recovered transcript is ONLY saved to SQLite for UI/History. Do NOT replace the `Content.AudioBytes` in the active `ConversationSession` context to avoid invalidating the multimodal KV-cache indices.

## 6. Audio Persistence & Pruning Lifecycle
- [x] 6.1 Zero-Overhead WAV Encoder: Implement `WavEncoder` to write a 44-byte RIFF header directly to the PCM byte stream to avoid CPU-intensive `MediaCodec` compression during the TTFA critical path.
- [x] 6.2 Storage Domain Setup: Target `context.filesDir.resolve("audio_records")` for persistence to protect against OS cache evictions during Whisper inference. Update the UI to gracefully handle missing files gracefully (e.g. rendering a disabled play button instead of crashing).
- [x] 6.3 Two-Tier Eviction Model: 
  - Never auto-prune `FAILED` transcripts.
  - Implement a 50MB Inline LRU Quota in the `AudioRecorder` flow to prune the oldest `COMPLETED` files.
  - Implement a `WorkManager` background job (48-hour TTL) to sweep old `COMPLETED` files.
- [x] 6.4 Cascade Deletion Hooks: Ensure LRU and TTL file deletions execute atomic Room queries (`UPDATE chat_messages SET audioFilePath = NULL`). Add hook to the "Delete Conversation" UI action to wipe associated files from disk before dropping the SQLite rows.

## 7. UI Waveform Player Architecture
- [x] 7.1 Zero-Jank Waveform Extraction: Implement `WaveformExtractor` to process the PCM byte array in memory (before file writing), grouping into 48 buckets, calculating RMS, and normalizing to an 8-bit `ByteArray` (0-100). Store this 48-byte BLOB in `ChatMessageEntity.waveformData`.
- [x] 7.2 The Voice Note Card Layout: Implement Paradigm A. The message bubble retains a persistent audio player header (Play/Pause + Waveform). The transcript area displays a 2-line shimmer while `PENDING`, crossfading smoothly to the actual text when `COMPLETED`, preventing `LazyColumn` layout jumps.
- [x] 7.3 Single-Instance Media3 Controller: Instantiate a single `AudioPlaybackController` (wrapping `ExoPlayer`) in the `ChatViewModel` to prevent `AudioTrack` exhaustion. Expose a `StateFlow<PlaybackState>` containing the active message ID and playback progress (polled at 60Hz).
- [x] 7.4 Waveform Canvas Composable: Build a lightweight `Canvas` to draw the 48 waveform bars using rounded strokes. Color bars up to the current `PlaybackState.progress` with the active color, dimming the rest, avoiding path allocations during recomposition.
