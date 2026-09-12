## 1. Strategy plumbing (D1)

- [x] 1.1 In `AssistantSession.startTurn`, capture the resolved `VoiceInputStrategyResult` and pass a `useDirectAudio` flag (or the resolution) into `executeDirectAudioTurn`, `executeTranscribeTurn`, and `handleFollowUpLoop`
- [x] 1.2 In `handleFollowUpLoop`, branch on strategy: `DIRECT_AUDIO` → skip `transcribeVerdict` entirely; `STT_TRANSCRIBE` → keep existing transcription path

## 2. Native audio follow-up routing (D1)

- [x] 2.1 In `handleFollowUpLoop` (DIRECT_AUDIO branch), encode captured `audioFloats` via `WavEncoder.pcmFloatsToWav` and call `voiceSession.processUtterance(userInput = "", audioBytes = wavBytes, enableTts = false, source = "VOICE_FOLLOW_UP")`
- [x] 2.2 Remove the transcript-empty/"didn't catch that" handling for DIRECT_AUDIO captured speech; keep `isSilentBuffer` as the no-speech gate and the existing retry-then-exit behavior for silence
- [x] 2.3 Switch follow-up capture to the TTS-gated `recordGatedUtterance` pattern so the assistant's own TTS tail is not ingested as the reply
- [x] 2.4 Remove/bypass `handleVerbalConfirmation` call sites on voice turns (initial turn, transcribe turn, follow-up loop) — no `ConfirmationRequired` interception on voice

## 3. Conversational confirmation in ConversationSession (D2)

- [x] 3.1 In `processUtterance`, run the `requiresConfirmation` gate/suspension block only for chat sources (`source == "TEXT"` / non-voice); define a voice-source set (`VOICE`, `DIRECT_AUDIO`, `VOICE_FOLLOW_UP`)
- [x] 3.2 For gated tool calls on voice sources: do not execute, do not open a channel; append a tool-result turn instructing the model to ask a confirmation question (contact name + full phone number) and invoke the tool only after verbal confirmation
- [x] 3.3 In `buildSystemPrompt`, add the confirmation contract: always confirm before `call_contact` by stating contact name and full phone number in a question; invoke only after verbal affirmation; re-ask on ambiguous replies
- [x] 3.4 Verify the coached-deferral turn terminates within `maxIterations` (loop iteration accounting)

## 4. Cleanup (D3)

- [x] 4.1 Confirm `ChatViewModel` and other consumers use UI buttons (not `ConfirmationKeywords`) for chat confirmation
- [x] 4.2 Delete `ConfirmationKeywords.kt` and remove `handleVerbalConfirmation`; inline/remove `transcribeVerdict` if only the STT_TRANSCRIBE follow-up path still needs it
- [x] 4.3 Remove now-dead `AssistantState.AwaitingVerbalConfirmation` handling if unused, and unused confirmation constants/imports

## 5. Tests

- [x] 5.1 `AssistantSessionTest`: remove `handleVerbalConfirmation` tests; add DIRECT_AUDIO follow-up test — fake recorder returns speech PCM, fake LLM receives `audioBytes` (non-null WAV) with `source = "VOICE_FOLLOW_UP"`, no STT call
- [x] 5.2 `AssistantSessionTest`: add STT_TRANSCRIBE follow-up regression test — transcript path unchanged
- [x] 5.3 `AssistantSessionTest`: add silent-capture test — silent buffer exits loop without invoking the LLM under either strategy
- [x] 5.4 `ConversationSessionTest`: voice source + `requiresConfirmation` tool → tool not executed, no `ConfirmationRequired` suspension, coached tool-result turn appended; second round with affirmative audio → tool executes
- [x] 5.5 `ConversationSessionTest`: chat source (`TEXT`) + gated tool → existing channel/timeout behavior asserted (regression guard)
- [x] 5.6 `ConversationSessionTest`: `buildSystemPrompt` contains the call_contact confirmation instruction
- [x] 5.7 `LocalToolsTest`: update expectations for conversational confirmation flow (describeAction/repeatBack usage on chat path)
- [x] 5.8 Run full unit test suite for `core/assistant`, `core/conversation`, `core/tools`, `core/ui` and fix regressions

## 6. Validation

- [x] 6.1 Verify Whisper fallback path intact: `STT_TRANSCRIBE` model still initializes `LiteRtWhisperEngine` lazily and transcribes
- [x] 6.2 Verify failed direct-audio turn still demotes to STT fallback (existing behavior unchanged)
- [x] 6.3 Device validation (RZCY219CJ2K, gemma-4-E4B-it): "call Mom" → disambiguation question → verbal pick → confirmation question → verbal "yes" → call placed, with turn logs showing `VOICE_FOLLOW_UP` audio turns and no STT invocation
