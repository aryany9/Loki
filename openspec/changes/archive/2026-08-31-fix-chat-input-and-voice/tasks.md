## 1. Bug 1 — IME resize

- [x] 1.1 Add `android:windowSoftInputMode="adjustResize"` to `MainActivity` in `app/src/main/AndroidManifest.xml`
- [x] 1.2 Manual: tapping the chat TextField no longer pans the window to the top bar; composer rises above keyboard; Setup/Settings screens' keyboard behavior still correct

## 2. Voice strategy resolver

- [x] 2.1 Create `VoiceInputStrategyResolver` (core/assistant): sealed result `DirectAudio / SttTranscribe / Unavailable(reason)` from `ModelLibraryManager` capability check (`isAudioInputSupported` VERIFIED/USER_CONFIRMED) + `isRuntimeReady(ModelRuntime.LITERT_ASR)` + `SttEngine` presence
- [x] 2.2 Refactor `AssistantSession` to use the resolver (behavior-identical); verify assistant voice turns still work
- [x] 2.3 Unit test resolver: audio-capable record → DirectAudio; text-only + STT ready → SttTranscribe; text-only + STT not ready → Unavailable(STT_NOT_READY); no active model → Unavailable(NO_ACTIVE_MODEL)

## 3. Chat voice path rewiring

- [x] 3.1 `ChatViewModel`: add `voiceError: StateFlow<String?>`; rewire `startVoiceInput()` through the resolver
- [x] 3.2 DirectAudio path: `AudioRecorder.recordUtterance()` → `WavEncoder.pcmFloatsToWav` → `chatSession.processUtterance(userInput = "", audioBytes = wav, enableTts = false, source = "CHAT_DIRECT_AUDIO")` with existing streaming/tool/persistence flow; ensure cancellation disposes the recorder
- [x] 3.3 SttTranscribe path: lazily initialize whisper from model storage; transcribe → `sendMessage(text)`
- [x] 3.4 All failure paths (resolver Unavailable, `SttEvent.Error`, recording errors) set `voiceError`; never silently reset
- [x] 3.5 `ChatScreen`: render `voiceError` as a dismissible banner above the composer
- [x] 3.6 Unit test: DirectAudio turn sends audioBytes through session and finalizes message; Unavailable sets `voiceError` and does not start recording

## 5. Theme module extraction (long-term fix — see design D2)

- [x] 5.1 Create `core/theme` module (android library, compose enabled, package `dev.loki.android.core.theme`); register in `settings.gradle.kts`
- [x] 5.2 Move `LokiTheme.kt`, `Type.kt`, `Shapes.kt`, `LokiTokens.kt`, `ThemeRepository.kt` from `core/ui/.../theme/` to `core/theme`, renaming package to `dev.loki.android.core.theme`; delete the old `theme/` package from `core/ui`
- [x] 5.3 Update all imports of `dev.loki.android.core.ui.theme.*` across `core/ui` and `app` to `dev.loki.android.core.theme.*` (incl. `AgentConfigRepository`'s `dataStore` extension import — must remain a single definition)
- [x] 5.4 Add `implementation(project(":core:theme"))` to `core/assistant` and `core/ui`; confirm `core/assistant` has NO dependency on `core:ui`
- [x] 5.5 Restore `LokiTheme { }` in `LokiVoiceInteractionSession.kt` (from `dev.loki.android.core.theme`), replacing the plain `MaterialTheme` downgrade
- [x] 5.6 Verify: `./gradlew :app:assembleDebug` passes; grep confirms no remaining `dev.loki.android.core.ui.theme` references anywhere

## 6. STT provisioning (text-only model fallback)

- [x] 6.1 Add a bundled-catalog entry for a whisper TFLite ASR model (e.g. `whisper-tiny.en`, runtime `LITERT_ASR`) with correct artifact URLs + SHA-256
- [x] 6.2 Chat SttTranscribe path: when ASR record is NOT_DOWNLOADED, set actionable `voiceError` with a one-tap download action reusing `ModelDownloader`; after download+LOAD, mic works offline
- [x] 6.3 Unit test: resolver returns SttTranscribe when an ASR record is LOADED; voiceError download action path registers the ASR model
- [x] 6.4 Confirm Android `SpeechRecognizer` is not used for chat voice input

## 7. Validation

- [x] 7.1 `./gradlew test :app:assembleDebug` passes
- [x] 7.2 Manual: mic on audio-capable model records → response streams into chat with `[Voice Audio]` bubble; stop button cancels mid-record cleanly
- [x] 7.3 Manual: with a text-only model (or no STT model), mic tap shows visible error banner instead of nothing
- [x] 7.4 Manual: assistant lock-screen voice flow unchanged (direct audio turn works as before)
- [x] 7.5 Run `openspec validate fix-chat-input-and-voice` and confirm all tasks complete
