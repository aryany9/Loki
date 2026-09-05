## 1. Unique Exact-Match Pre-Selection (Optimization C)

- [x] 1.1 In `ConversationSession.kt` (and `CallContactTool`), add exact display-name matching check when `lookup_contact` returns multiple contacts
- [x] 1.2 If `exactMatches.size == 1`, auto-select the candidate, assign `selectedId`, and skip `CONTACT_DISAMBIGUATION`
- [x] 1.3 Add unit tests verifying that "Call Mom" with ["Mom", "Suraj's Mom", "Prashik's Mom"] directly selects "Mom" without entering disambiguation

## 2. State-Scoped Tool Grammar Gating (Foundation B)

- [x] 2.1 Update `ToolRegistry.getAvailableTools` and `GrammarBuilder` to accept `TaskState` and filter legal tools per state
- [x] 2.2 During `CONTACT_DISAMBIGUATION` (`selectedId == null`), restrict grammar strictly to `select_contact` (exclude `ask_user`, `call_contact`, `lookup_contact`)
- [x] 2.3 During `CALL_CONFIRMATION` (`selectedId != null`, unconfirmed), exclude `call_contact` from grammar until confirmation is received
- [x] 2.4 Add unit tests for `GrammarBuilder` and `ToolRegistry` ensuring grammar strings disallow out-of-state tool names

## 3. Contact Selection & Confirmation State Transitions

- [x] 3.1 Implement `select_contact` tool handling in `ConversationSession.kt` to validate candidate ID membership and transition to `CALL_CONFIRMATION`
- [x] 3.2 Implement confirmation handling in `ConversationSession.kt`: on affirmation, transition to `CONFIRMED` and invoke `call_contact`; on denial, respond conversationally and clear task state
- [x] 3.3 Add unit tests simulating full multi-turn flows: Turn 1 (lookup -> disambiguate) -> Turn 2 (select_contact) -> Turn 3 (affirmation -> call)

## 4. Prompt Simplification & Cleanup

- [x] 4.1 Remove negative prompt enforcement rules from `getCapabilityInstructions` and `renderTaskState`
- [x] 4.2 Replace with declarative semantic state descriptions explaining the current active task
- [x] 4.3 Verify unit test suite passes across `ConversationSessionTest`, `AssistantSessionTest`, and `ToolRegistryTest`
