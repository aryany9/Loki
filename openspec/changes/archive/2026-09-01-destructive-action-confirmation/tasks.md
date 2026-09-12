## 1. Tool interface & registry

- [x] 1.1 Add to `Tool` (`core/tools/Tool.kt`): `val requiresConfirmation: Boolean get() = false` and `fun describeAction(arguments: Map<String, Any?>): String = name`
- [x] 1.2 Expose confirmation metadata from `ToolRegistry` (lookup by tool name → `requiresConfirmation` + `describeAction(parsedArguments)`); unit test both

## 2. ConversationSession confirmation gate

- [x] 2.1 Add `ConversationEvent.ConfirmationRequired(toolName: String, repeatBack: String)`; introduce `PendingConfirmation` state (toolName, arguments, `CompletableDeferred<Boolean>`) and `respondToConfirmation(accepted: Boolean)` on `ConversationSession`
- [x] 2.2 In the tool loop: when `requiresConfirmation` → emit the event, `withTimeout(20_000)` on the deferred; accepted → execute as today; denied/timeout → append a `ToolExecutionResult` turn ("User declined the action." / "No response received; action cancelled."); unit test accepted/denied/timeout paths via `runTest`
- [x] 2.3 Make `cancelGeneration()` resolve any pending confirmation as denied; unit test no zombie gate after cancellation

## 3. Chat confirmation card

- [x] 3.1 `ChatViewModel`: expose `pendingConfirmation: StateFlow<PendingConfirmation?>` + `respondToConfirmation(accepted)`; wire `ConversationEvent.ConfirmationRequired` handling; unit test card state set/clear
- [x] 3.2 `ChatScreen`: confirmation card above the composer (repeat-back text, tool name, Confirm/Cancel buttons) styled with theme tokens; manual check the full chat confirm/deny flow

## 4. Voice overlay verdict capture

- [x] 4.1 Add `AssistantState.AwaitingVerbalConfirmation(repeatBack: String)`; `AssistantSession`: on `ConfirmationRequired` speak the repeat-back via the existing TTS path, then transition to `AwaitingVerbalConfirmation` without stopping the recorder; keyword-match the next transcript via a `ConfirmationKeywords` object (yes/no sets); non-match → one re-prompt ("Sorry — yes or no?") then fall through to timeout; unit test verdict resolution for yes/no/re-prompt
- [x] 4.2 `LokiVoiceInteractionSession`: render the repeat-back prominently with the equalizer in reactive mode + subtle pulse on the text; visually distinct from plain Listening; manual check on the lock-screen assistant: repeat-back spoken → mic stays live → "yes" executes, "no" cancels

## 5. Gate call_contact

- [x] 5.1 `CallContactTool`: set `requiresConfirmation = true`; implement `describeAction` resolving contact name (when the argument is a name) → "Call <name> at <number>?"; unit test the repeat-back string; confirm `DialNumberTool` stays ungated

## 6. Validation

- [x] 6.1 `./gradlew test :app:assembleDebug` passes; all existing LocalToolsTest / ConversationSession / AssistantSession tests still pass
- [x] 6.2 Manual matrix: chat gated call (confirm → executes; cancel → model reacts), voice gated call (yes/no/unrelated utterance), dial_number ungated, timeout auto-cancel, generation-cancel during pending gate
- [x] 6.3 `openspec validate destructive-action-confirmation` passes; tick all tasks