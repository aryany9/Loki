## Why

Three voice-assistant behaviors fail on a real device:

1. After Loki answers, the overlay flashes to a static "Ready" label the instant the turn
   ends, discarding the response text the user was just reading.
2. "Call Mom" guesses one contact and immediately proposes calling that person, instead of
   listing every matching "Mom" contact and letting the user pick one.
3. During a verbal-confirmation re-prompt, the user's real reply is swallowed. The 250 ms
   blind delay after TTS plus a fresh recorder restart causes Loki's own TTS tail to be
   re-parsed as an unrecognized utterance twice, which auto-cancels the action. The user
   experiences this as "it didn't wait for my reply."

Together these make the voice confirmation flow flaky and unhelpful, and they hide the
assistant's answer from the screen.

## What Changes

- **Persist the last assistant answer instead of flashing to "Ready".** After a successful
  answered voice turn, the overlay keeps displaying the final response text until the next
  turn begins or the session is dismissed. The static "Ready" Idle label appears only when
  there is no prior answer to show.
- **Ambiguous contact disambiguation.** `LookupContactTool` returns ALL matching contacts
  (deduplicated, each with name and number), not just the first match. The system prompt
  gains guidance so the model lists the matches and asks which one to call when a requested
  contact name matches multiple people, before invoking `call_contact` for a single
  selection. A unique single match keeps the existing direct-confirm flow.
- **Audio-domain confirmation capture.** Replace the blind post-TTS `delay(250)` and the
  fresh-recorder restart with a continuously armed microphone whose committed-audio window
  is gated on the TTS state (`isSpeaking` / `onDone`). The user's reply is not clipped and
  Loki's own TTS tail is not ingested as user input. Add a confirmation timeout mirroring
  the chat path's `CONFIRMATION_TIMEOUT_MS`; on expiry the voice path feeds a denial result
  back to the model so it responds naturally, instead of replaying a hardcoded "cancelled".

- **Preserve assistant-initiated navigation deep links (incidental fix).** `MainActivity`
  gains `onNewIntent` handling for the `openScreen` extra (PERMISSIONS, MODEL_LIBRARY,
  AGENT_PLAYGROUND, MEMORY, SETTINGS). Without this, the assistant's "open permissions"
  intent is silently dropped when `MainActivity` is already alive in the background
  (`FLAG_ACTIVITY_NEW_TASK` recycles the activity without redelivering the intent), so the
  user lands on whatever screen they were on instead of the Permissions screen. This fix is
  required by the voice confirmation flow's `PERMISSION_OPENED` outcome (D3's related
  path).

Scope guard: the broader audio capability layer (probing AcousticEchoCanceler,
NoiseSuppressor, AutomaticGainControl, input presets, VAD ownership, barge-in from TTS) is
parked in the roadmap as a separate later change. This proposal fixes the behavioral
symptoms and lays a compatible seam (a commit window gated on TTS state) that the parked
layer can later back with AEC/NS/AGC. No AEC/DSP/VAD work lands here.

## Capabilities

### New Capabilities
- `voice-confirmation-capture`: the confirmation-verdict listening contract for voice. Covers
  the TTS-state-gated audio commit window, the echo guard for re-prompt text, the
  timeout-to-denial-result semantics that unify with the chat confirmation path, and the
  guarantee that two listen attempts resolve before giving up.

### Modified Capabilities
- `voice-interaction-ui`: response-text persistence. The overlay keeps showing the final
  response text after the turn completes instead of reverting to an empty "Ready" Idle state.
- `local-android-tools`: `lookup_contact` returns all matching contacts (deduplicated, with
  numbers), and an ambiguous contact name leads to listing matches and asking which one,
  rather than silently picking the first.
- `action-confirmation`: voice-side denial and timeout feed a denial `ToolResult` back to the
  model (so it replies conversationally) and use the chat-defined `CONFIRMATION_TIMEOUT_MS`,
  replacing the hardcoded post-re-prompt "cancelled" terminal and unifying timeout semantics
  between voice and chat.
- `app-shell`: assistant-initiated navigation intents (e.g. open the Permissions screen from
  the voice overlay) are honored even when `MainActivity` is already running.
- `voice-follow-up-turns` (new): a voice turn ending in a question keeps listening and feeds
  the spoken answer back as a follow-up turn on the same session (max 3 rounds, one retry on
  silence, graceful completion).

## Impact

- `core/assistant`: `AssistantSession.kt` (`handleVerbalConfirmation`, `listenForVerdict`,
  and the turn-completion transition in `startTurn`). Answer-persist state transition,
  TTS-gated commit window, timeout handling.
- `core/voice/stt`: `AudioRecorder.kt`. Support a continuously armed stream with a commit
  window (start committing on TTS `onDone`, stop or commit on end of speech). The inference
  path is unchanged: Whisper remains the default STT, and the direct-audio LLM path remains
  the fallback when the active model lacks audio input capability.
- `core/conversation`: `ConversationSession.kt`. Expose the existing
  `CONFIRMATION_TIMEOUT_MS` contract to the voice path and ensure a denial result is produced
  on voice timeout, aligning the voice and chat paths. `buildSystemPrompt` gains
  disambiguation guidance for ambiguous contact names.
- `core/tools/local`: `LookupContactTool.kt` returns all matches; `LocalToolsTest` updates.
- `core/assistant`: `LokiVoiceInteractionSession.kt`. Render the persisted answer state and
  the awaiting-confirmation re-prompt correctly.
- `app`: `MainActivity.kt`. `onNewIntent` handling for the assistant's `openScreen` deep
  link so assistant-initiated navigation works when the activity is already alive.
- Specs updated: `voice-interaction-ui`, `local-android-tools`, `action-confirmation`,
  `app-shell`; new capability `voice-confirmation-capture`.
