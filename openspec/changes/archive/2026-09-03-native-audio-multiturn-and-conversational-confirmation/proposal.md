## Why

Two failures on audio-capable LLMs (`gemma-4-E4B-it`, `DIRECT_AUDIO`) break multi-turn voice interaction:

1. **Follow-up audio is discarded.** `AssistantSession.handleFollowUpLoop` unconditionally transcribes the user's reply via Whisper STT. On `DIRECT_AUDIO` the Whisper runtime is not loaded, so the transcript is empty, 72,000 bytes of valid speech are treated as silence, the assistant says "I didn't catch that", and the loop exits without executing the user's command.
2. **Verbal confirmation auto-denies.** When `call_contact` emits `ConfirmationRequired`, the voice path suspends on `confirmationChannel` and `handleVerbalConfirmation` parses an STT transcript against the rigid `ConfirmationKeywords` regex. With Whisper inactive (`sttEngine == null`) the verdict is an immediate silent denial ("User declined the action."), and even with STT, natural affirmations ("yep do that", "sounds good") fail keyword matching.

Both failures share one root cause: the voice pipeline treats confirmation and follow-up as *parsing* problems solved with hardcoded program logic, when the active LLM — which already receives raw audio natively — is the only component capable of holding a conversation. The fix is architectural: route native audio on every round, and let the LLM conduct confirmation conversationally.

## What Changes

- **Native audio routing for all rounds.** On `DIRECT_AUDIO`, every multi-turn round (initial utterance, disambiguation follow-ups, and conversational confirmations) sends recorded PCM encoded as WAV directly to the LLM. Whisper STT is used for multi-turn transcription only when the active model lacks audio input (`STT_TRANSCRIBE`).
- **Conversational action confirmation on voice.** Remove the programmatic confirmation gate from voice turns: `processUtterance` no longer suspends on `confirmationChannel`/`gate.deferred` for voice sources (`"VOICE"`, `"DIRECT_AUDIO"`, `"VOICE_FOLLOW_UP"`). The system prompt gains an explicit contract instructing the model to ask a confirmation question (contact name + full phone number) before invoking `call_contact` and to execute only after the user's verbal affirmation. The follow-up loop carries the user's natural reply ("yes, you are right", "sure", "no") back to the model, which decides whether to execute.
- **Remove `handleVerbalConfirmation` and `ConfirmationKeywords` from the voice path.** The STT-based verdict state machine and keyword regex matcher are deleted (chat UI is unaffected).
- **Chat UI confirmation preserved.** `ChatViewModel` keeps the programmatic `confirmationChannel`, `PendingConfirmation` state, and `[Confirm]` / `[Cancel]` buttons for text-chat turns (`source = "TEXT"`).
- **Whisper fallback retained.** Lazy `LiteRtWhisperEngine` initialization stays for non-audio models; the STT auto-demotion path for failed direct-audio turns is unchanged.
- **Reuse gated capture.** The TTS-echo-protected `recordGatedUtterance` capture from the previous change is reused for conversational follow-up/confirmation rounds.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `voice-input-strategies`: follow-up and confirmation rounds on `DIRECT_AUDIO` must route native audio to the LLM instead of STT; STT-transcribe remains fallback-only for non-audio models.
- `voice-pipeline`: multi-turn follow-up capture must not depend on an STT engine when the active strategy is direct-audio; gated microphone capture is reused across follow-up rounds.
- `action-confirmation`: confirmation becomes source-dependent — voice turns rely on model-driven conversational confirmation (no channel suspension, no keyword verdict); chat turns keep the explicit-gate requirements.

## Impact

- `core/assistant/src/main/java/dev/loki/android/core/assistant/AssistantSession.kt` — `executeDirectAudioTurn`, `executeTranscribeTurn`, `handleFollowUpLoop` (strategy-aware routing); remove `handleVerbalConfirmation` + `transcribeVerdict` voice usage.
- `core/assistant/src/main/java/dev/loki/android/core/assistant/ConfirmationKeywords.kt` — deleted (after confirming no remaining consumers).
- `core/conversation/src/main/java/dev/loki/android/core/conversation/ConversationSession.kt` — source-dependent confirmation gate in `processUtterance`; confirmation guidance in `buildSystemPrompt`.
- `core/voice/stt/src/main/java/dev/loki/android/core/voice/stt/LiteRtWhisperEngine.kt` — unchanged (fallback only); verify.
- Tests: `AssistantSessionTest.kt`, `ConversationSessionTest.kt`, `LocalToolsTest.kt`.
- Supersedes parts of the completed change `fix-voice-confirmation-and-persist-answer` on the voice path (its gated recorder and disambiguation behavior are retained).
