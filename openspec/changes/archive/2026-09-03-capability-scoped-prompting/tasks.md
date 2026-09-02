## 1. Capability identity & registry scoping (D1, D2, D5)

- [x] 1.1 Add `capability: String` (default `"general"`) to the `Tool` interface
- [x] 1.2 Annotate all local tools with their capability (`calling`: lookup_contact, call_contact, dial_number; `device`/`media`/etc. per tool) and assign `general` per the D2 governance rule (remember_fact, search_chat_history, get_current_time, get_battery_status)
- [x] 1.3 Add `activeCapability: String?` parameter to `ToolRegistry.getAvailableTools`/`getDisabledTools` implementing the three-filter rule (permission ∩ environment ∩ capability scope) with full visibility when null
- [x] 1.4 Surface out-of-scope tool calls through the existing coached-deferral corrective path (no execution)
- [x] 1.5 Unit tests: scoped query (active/null), general-governance enumeration test, out-of-scope call correction

## 2. TaskState contract (D3, D6)

- [x] 2.1 Define sealed `TaskState` in `core/conversation` with `advancingTool: String?` and `resolved: Boolean`
- [x] 2.2 Implement `ContactResolution` variant (candidate IDs + names only, `selectedId`, `confirmed`), with `advancingTool` derived from its fields
- [x] 2.3 Wire session-owned `taskState` + `activeCapability` into `ConversationSession` with activation/deactivation rules (tool-call activation, completion/cancellation deactivation, pending-state switch blocking via coached deferral)
- [x] 2.4 Implement app-side validation of advancing tool calls (ID membership, confirmation prerequisite) and app-side ID→phone-number resolution for `call_contact`
- [x] 2.5 Unit tests: state transitions, invalid-ID correction, advancingTool visibility invariant, pending-state switch blocking

## 3. Per-turn prompt composition (D4)

- [x] 3.1 Refactor `buildSystemPrompt` into core composer (persona, JSON protocol, language, memories — KV-prefilled) + per-turn composer (scoped tool schemas, capability instructions, task-state block, disabled notices)
- [x] 3.2 Move tool-schema listing out of the KV-prefilled core prompt into the per-turn composition (full set at session start; scoped set while active)
- [x] 3.3 Implement capability instruction injection (full on activation, 1-line reminder while active) with a `calling` capability prompt; remove the monolithic contact-guidance paragraph from the core prompt
- [x] 3.4 Render the task-state block fresh each turn from `TaskState` (names + app-issued IDs, no numbers)
- [x] 3.5 Update `formatFastPathResponse` for name-based `call_contact` announcement (app-resolved name, never the raw number)
- [x] 3.6 Unit tests: composer outputs (core vs per-turn), scoped schema listing, state-block rendering without numbers, name-based call announcement

## 4. Grammar scoping (D5)

- [x] 4.1 Pass the scoped visible tool set to `GrammarBuilder.buildFrom` and regenerate on capability activation/deactivation
- [x] 4.2 Unit tests: grammar encodes only scoped tool names; regeneration on scope change

## 5. Contact tool updates (D6)

- [x] 5.1 `LookupContactTool`: return candidate IDs + names + numbers (numbers consumed app-side only)
- [x] 5.2 `CallContactTool`: accept `candidate_id` (app-resolved number) alongside the raw-number chat path; fix result to report the contact NAME for spoken rendering
- [x] 5.3 Implement `select_contact` handling (internal reserved tool or reserved JSON shape per design open question)
- [x] 5.4 Unit tests in `LocalToolsTest`: candidate-ID flow, name-based result data, validation errors

## 6. Integration & regression

- [x] 6.1 Update `ConversationSessionTest`: capability lifecycle, per-turn composition, contact end-to-end (lookup → select → confirm → call with app-resolved number)
- [x] 6.2 Verify chat path unchanged: full tool visibility at session start, confirmation gate and `[Confirm]`/`[Cancel]` buttons intact
- [x] 6.3 Verify voice path: capability + state survive across the multi-round follow-up loop on the held voice session
- [x] 6.4 Run full unit suites for `core/tools`, `core/conversation`, `core/assistant`, `core/ui` and fix regressions
- [x] 6.5 Device validation (gemma-4-E4B-it): "Call Mom" with 6 matches → names-only disambiguation question → "the second one" → natural confirmation question → "yes, go ahead" → call placed, TTS says the contact name, turn logs show scoped tool sets and no numbers in model context
