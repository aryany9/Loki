## 1. Core Model — `ConfirmationOutcome` enum

- [x] 1.1 Add `enum class ConfirmationOutcome { CONFIRMED, DECLINED, UNKNOWN }` to `core:conversation` (e.g. alongside `TaskState.kt`)
- [x] 1.2 Add `companion object { fun from(raw: String): ConfirmationOutcome }` that maps `"CONFIRMED"` / `"DECLINED"` to their values and any other string to `UNKNOWN`

## 2. `ConversationManager` State Transition API

- [x] 2.1 Add `fun confirmContactResolution()` to `ConversationManager` — copies `taskState as ContactResolution` with `confirmed = true` and writes it back; no-op if `taskState` is not a `ContactResolution` with `selectedId != null && !confirmed`
- [x] 2.2 Add `fun clearVoiceTask()` to `ConversationManager` — sets `taskState = null`, `pendingVoiceAsk = null`, `pendingVoiceConfirmation = null`; no-op if already clear
- [x] 2.3 Add unit tests to `ConversationManagerTest` for `confirmContactResolution()` and `clearVoiceTask()` covering guard conditions and expected state transitions

## 3. `ConfirmationResolver` Implementation

- [x] 3.1 Create `ConfirmationResolver.kt` in `core:assistant` with `suspend fun resolve(audioBytes: ByteArray?, transcript: String?, question: String, llmEngine: LiteRtLlmEngine): ConfirmationOutcome`
- [x] 3.2 Implement classification prompt construction: pending question + multilingual semantic anchors for `CONFIRMED` / `DECLINED` / `UNKNOWN` (no conversation history, no tool schemas)
- [x] 3.3 Implement GBNF grammar string: `root ::= "CONFIRMED" | "DECLINED" | "UNKNOWN"`
- [x] 3.4 Call `llmEngine.generate(prompt = ..., audioBytes = audioBytes, grammar = grammar)` with `audioBytes` on DirectAudio path and text-embedded prompt on STT-Transcribe path
- [x] 3.5 Map raw engine output to `ConfirmationOutcome` via `ConfirmationOutcome.from(result.trim())`
- [x] 3.6 Write unit tests for `ConfirmationResolver`: CONFIRMED/DECLINED/UNKNOWN mapping, malformed engine output → UNKNOWN, null audio + transcript path, null transcript + audio path

## 4. `AssistantSession` — Follow-up Loop Branch

- [x] 4.1 In `handleFollowUpLoop()`, after capturing audio and before creating a new `ConversationSession`, read `conversationManager.taskState?.expectedSemantics`
- [x] 4.2 When `expectedSemantics == ExpectedResponseSemantics.CONFIRMATION`, call `ConfirmationResolver.resolve()` with `audioBytes` (DirectAudio) or `transcript` (STT-Transcribe) and `conversationManager.pendingVoiceAsk?.question`
- [x] 4.3 Handle `CONFIRMED`: call `conversationManager.confirmContactResolution()`, then create a new `ConversationSession` with the updated `taskState` and continue the loop (the model will invoke `call_contact`)
- [x] 4.4 Handle `DECLINED`: call `conversationManager.clearVoiceTask()`, speak "Okay, I've cancelled that." via TTS, set `currentTurnEndedInAskUser = false`, break loop
- [x] 4.5 Handle `UNKNOWN`: leave state unchanged, leave `currentTurnEndedInAskUser = true`, continue to next loop iteration (existing re-prompt path via `currentResponse` unchanged)
- [x] 4.6 Ensure the branch is skipped (falls through to full-session path) when `taskState == null` or `expectedSemantics != CONFIRMATION`

## 5. Verification & Spec Alignment

- [x] 5.1 Write integration test in `AssistantSessionTest` (or a new `ConfirmationResolverIntegrationTest`): simulate `AWAITING_CONFIRMATION` → mock resolver returns `CONFIRMED` → verify `confirmContactResolution()` is called and loop creates a new session; repeat for `DECLINED` (clearVoiceTask called, loop exits) and `UNKNOWN` (loop continues, question re-spoken)
- [x] 5.2 Verify that the chat confirmation path (`source == TEXT`, `PendingConfirmation` deferred, `respondToConfirmation()`) is completely unaffected by diffing `ConversationSession` — no new code paths should touch the chat flow
- [x] 5.3 Device smoke test: "Call Mom" → model asks question → user says "No" → TTS speaks cancellation → mic off ✓; "Call Mom" → model asks → user says "Haan" → call placed ✓; "Call Mom" → model asks → user says unclear audio → model re-prompts → user says "yes" → call placed ✓
