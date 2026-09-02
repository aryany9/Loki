## 1. Answer persistence (D1)

- [x] 1.1 Add a terminal completed state to `AssistantState` carrying the final response text
- [x] 1.2 Rework the turn-completion path in `AssistantSession.startTurn()`: successful answered turns hold the completed state; silence/empty-speech/error still fall back to `Idle`; state clears on the next `startTurn()` or `dismiss()`
- [x] 1.3 Render the completed state in `LokiVoiceInteractionSession` (response text visible, equalizer settled) instead of the static "Ready" label
- [x] 1.4 Unit tests: state persists after a successful answered turn, falls back to `Idle` on silence/error, and clears on the next turn or dismiss

## 2. Contact disambiguation (D2)

- [x] 2.1 `LookupContactTool`: collect all unique matching contacts (deduplicated by normalized name/number, capped at 10) and return them with name and number in the structured result; keep the `NOT_FOUND` error behavior
- [x] 2.2 Update `LocalToolsTest` for the new multi-match result shape
- [x] 2.3 Add disambiguation guidance to `ConversationSession.buildSystemPrompt`: multiple matches -> list them with numbers and ask which one to call before `call_contact`; unique match -> proceed to the gated call
- [x] 2.4 Unit test: prompt includes the disambiguation guidance

## 3. Confirmation capture (D3)

- [x] 3.1 `AudioRecorder`: support a continuously armed capture with a commit window - do not commit samples while `tts.isSpeaking()` is true, begin committing on the TTS `onDone` callback with a short rolling lookback (120-180 ms), and end on the existing energy VAD
- [x] 3.2 Rework `AssistantSession.handleVerbalConfirmation` to use the armed capture through the repeat-back and re-prompt, removing the `delay(250)` and fresh-recorder restart; keep the two-attempt and echo-guard flow
- [x] 3.3 Add the confirmation timeout mirroring `CONFIRMATION_TIMEOUT_MS`: on expiry resolve the gate as denied and record the denial tool-result so the model replies naturally; remove the hardcoded "cancelled" terminal
- [x] 3.4 Unit tests: TTS-tail audio is not committed, a promptly spoken reply is captured, echo of the re-prompt stays unrecognized, and timeout produces the denial tool-result

## 4. Validation

- [x] 4.1 `./gradlew test :app:assembleDebug` passes; all existing tool/session tests green
- [x] 4.2 Manual device matrix: "what can you do?" keeps the answer on screen after TTS; "call mom" with multiple Mom contacts lists them and asks which one; a unique contact goes straight to the yes/no confirm; verbal "yes" executes, "no" cancels, silence or gibberish re-prompts once then cancels with a model-generated reply
- [x] 4.3 `openspec validate fix-voice-confirmation-and-persist-answer` passes; tick all tasks

## 5. Incidental fix documentation

- [x] 5.1 Document the `MainActivity` `onNewIntent`/`openScreen` deep-link handling in `design.md` (D4) and `proposal.md`; add the `app-shell` delta spec for assistant-initiated navigation
- [x] 5.2 Restore permission-aware tool filtering (`ToolRegistry.getAvailableTools`/`getDisabledTools` + disabled-tools prompt section) after it was removed out of scope; keep the D2 disambiguation guidance intact
- [x] 5.3 Revert `ConversationManager.context` visibility widening; `AssistantSession` permission call sites use the constructor context fallback

## 6. Post-device-verification fixes

- [x] 6.1 Revert the `AudioRecorder` noise-floor clamp (`minOf(rms, 400f)`) that broke end-of-speech detection and caused 30 s recordings
- [x] 6.2 Tighten disambiguation guidance: list contact matches by name only (never speak numbers), summarize when more than 3 match
- [x] 6.3 Follow-up listening turns after a question-ending response (D5): gated capture, 20 s timeout, one retry, max 3 rounds, `VOICE_FOLLOW_UP` re-entry, recorder released on all exits; new `AssistantState.AwaitingFollowUp` overlay state; documented in design (D5), proposal, and the `voice-follow-up-turns` delta spec
