# fix-chat-input-and-voice — Design

## Context

Bug 1: `AndroidManifest.xml` declares no `windowSoftInputMode` for `MainActivity` → platform default pans the window on IME open; Compose-side `imePadding()` (ChatScreen line ~358) is correct but defeated by the pan.

Bug 2: `ChatViewModel.startVoiceInput()` (line 239) unconditionally uses `sttEngine.startListening()`. `LiteRtWhisperEngine.initialize()` is never called in production code, so `startListening()` immediately emits `SttEvent.Error("Whisper model not initialized")`, which the ViewModel swallows (`_isRecording.value = false`). Meanwhile `AssistantSession` (line 41) implements the correct capability-driven strategy: `activeLlmRecord.capabilities.isAudioInputSupported` (from `ModelLibraryManager.manifest` + `ModelRecordCapabilities.isAudioInputSupported`, requiring VERIFIED/USER_CONFIRMED confidence) → DIRECT_AUDIO (AudioRecorder → WavEncoder → `processUtterance(audioBytes=…)`), else STT_TRANSCRIBE, gated by `modelManager.isRuntimeReady(ModelRuntime.LITERT_ASR)`.

## Goals / Non-Goals

**Goals:** resize-on-IME; chat mic uses the same strategy logic as the assistant; audio-capable models get direct audio in chat with zero whisper dependency; voice errors are visible.

**Non-Goals:** recording waveform UI, assistant behavior changes, STT model download UI, permission flow changes (RECORD_AUDIO already declared/granted in setup).

## Decisions

### D1: `adjustResize` manifest attribute only
One attribute on the `MainActivity` `<activity>` tag. No Compose changes — `imePadding()` is already correct. `SessionOverlay`/voice service unaffected (separate window, own IME handling).

### D2: Shared `VoiceInputStrategyResolver` + theme module extraction (long-term architecture fix)
`VoiceInputStrategyResolver` (sealed result: `DirectAudio`, `SttTranscribe`, `Unavailable(reason)` where reason ∈ {NO_ACTIVE_MODEL, NOT_AUDIO_CAPABLE, STT_NOT_READY}) lives in `core/assistant` and is consumed by both `AssistantSession` and `ChatViewModel`. This requires `core/ui → core/assistant` (ChatViewModel uses the resolver).

Because Gradle forbids cycles, `core/assistant → core/ui` must not exist — which previously carried `LokiTheme` into `LokiVoiceInteractionSession`. Rather than downgrading the voice overlay to plain `MaterialTheme` (regression against `loki-theme`), the theme is extracted into a new low-level module:

- **New module `core/theme`** (android library, Compose enabled, package `dev.loki.android.core.theme`): contains `LokiTheme.kt`, `Type.kt`, `Shapes.kt`, `LokiTokens.kt`, `ThemeRepository.kt` — moved from `core/ui/.../theme/`, package renamed from `dev.loki.android.core.ui.theme` → `dev.loki.android.core.theme` (no split packages; app is unreleased so import churn is free).
- `core/assistant` depends on `core:theme`; `LokiVoiceInteractionSession` uses `LokiTheme { }` again (Dynamic Color + tokens restored).
- `core/ui` depends on `core:theme`; all `dev.loki.android.core.ui.theme.*` imports across `core/ui` and `app` are updated to `dev.loki.android.core.theme.*`.
- **DataStore caveat**: `ThemeRepository.kt` defines the `Context.dataStore` extension also used by `AgentConfigRepository` (core/ui). It moves with the theme; `AgentConfigRepository`'s import is updated. Only one extension definition may exist (two DataStores on the same file would crash).
- Dependency graph after: `core/theme` ← {core/assistant, core/ui, app}; `core/ui → core/assistant` (kept, for the resolver); no `core/assistant → core/ui`.

*Rejected:* resolver relocation into `core/voice/stt` (minimal fix — leaves the theme trapped above the assistant forever and keeps the `AssistantSessionProvider.instance` global-singleton fallback); keeping the old package name across modules without rename (split packages — technically works, permanently confusing).


### D3: DIRECT_AUDIO chat turn — reuse chat session, not voice session
`startVoiceInput()` records via `AudioRecorder.recordUtterance()` (already suspend, VAD-gated), encodes WAV, then collects `chatSession.processUtterance(userInput = "", audioBytes = wav, enableTts = false, source = "CHAT_DIRECT_AUDIO")` through the same flow the composer uses — so streaming, tool calls, persistence, and UI all work unchanged. `[Voice Audio]` renders as the user bubble (existing behavior at ConversationSession line 78).
*Rejected:* `newVoiceSession()` — ephemeral context, would break chat persistence/streaming expectations.

### D4: Error surfacing via the existing model-banner pattern
Add a transient `voiceError: StateFlow<String?>` on `ChatViewModel`; `ChatScreen` shows it as a dismissible snackbar-style banner above the composer. All voice failure paths (UNAVAILABLE resolver result, `SttEvent.Error`, recording failures) set it. Also stops swallowing: `SttEvent.Error` still resets recording state AND sets the error message.

### D6: STT provisioning — whisper stays the STT engine, provisioned through the model library
Text-only models fall back to whisper (STT_TRANSCRIBE) — never Android `SpeechRecognizer`, which routes audio through Google's cloud on most OEMs and breaks Loki's offline-first/privacy identity (Edge Gallery's choice reflects its showcase purpose, not Loki's). The missing piece is provisioning: no ASR model exists in the catalog today, so `isRuntimeReady(LITERT_ASR)` is permanently false.

- Add a bundled-catalog entry for a small whisper TFLite ASR model (e.g. `whisper-tiny.en`, `ModelRuntime.LITERT_ASR`), downloadable via the existing `ModelDownloader` → manifest flow with the same SHA-256/atomic-write guarantees as LLM models.
- In the chat `SttTranscribe` path, resolve-and-initialize is already lazy (lazy resolve-and-initialize); now it can succeed: resolver → `SttTranscribe` when an ASR record is LOADED.
- Chat UX for text-only models: on first mic tap, if the ASR model is NOT_DOWNLOADED, set `voiceError` with an actionable message ("Voice model required — downloading…" flow into Model Library); a one-tap download action from the banner is in scope (reuses `ModelDownloader`), auto-download is not.
- Keep whisper quality/latency expectations documented: record-then-transcribe, no streaming partials — acceptable for assistant-style utterances.
- Future (roadlogged, not this change): pluggable `SttEngine` implementations could add an Android STT engine behind the same interface for users who prefer it; the resolver needs no changes for that.


## Risks / Trade-offs

- [Recording holds mic while UI shows stop-state; user cancel mid-record] → `stopVoiceInput()` already cancels; `AudioRecorder.recordUtterance()` cancellation must dispose `AudioRecord` — verify in implementation.
- [DIRECT_AUDIO chat turns lose the text transcript in history] → acceptable; `[Voice Audio]` placeholder matches assistant behavior; real transcription is a future enhancement.
- [AssistantSession refactor regresses assistant] → behavior-identical extraction; assistant validation steps in tasks.
- [adjustResize changes layout metrics on all screens] → imePadding already anticipated resize; verify Setup/Settings screens' keyboard behavior.

## Migration Plan

Manifest fix is one line and independently shippable. Strategy resolver + chat rewiring is additive; AssistantSession refactor is behavior-preserving. Rollback = revert; no data changes.

## Open Questions

None blocking.
