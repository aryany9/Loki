## MODIFIED Requirements

### Requirement: Follow-up loop branches on `ExpectedResponseSemantics` before session creation
`AssistantSession.handleFollowUpLoop()` SHALL read `conversationManager.taskState?.expectedSemantics` at the start of each round after audio capture. When the value is `ExpectedResponseSemantics.CONFIRMATION`, the loop SHALL call `ConfirmationResolver.resolve()` instead of creating a new `ConversationSession`. For all other semantics (`SELECTION`, `MISSING_SLOT`, `FREE_TEXT`, or null), the loop SHALL continue on the existing full-session path unchanged.

#### Scenario: CONFIRMATION semantic routes to resolver
- **WHEN** captured audio exists and `taskState.expectedSemantics == CONFIRMATION`
- **THEN** `ConfirmationResolver.resolve()` is called with the captured audio or transcript
- **AND** no new `ConversationSession` is created for this round
- **AND** the result determines the state transition per the `action-confirmation` spec

#### Scenario: Non-CONFIRMATION semantic routes to full session
- **WHEN** captured audio exists and `taskState.expectedSemantics != CONFIRMATION` (e.g. `SELECTION`)
- **THEN** the existing `ConversationSession` creation and `processUtterance()` path executes
- **AND** `ConfirmationResolver` is not called

#### Scenario: Null task state routes to full session
- **WHEN** `taskState` is null (no active task flow)
- **THEN** the existing full-session path executes unchanged

---

### Requirement: UNKNOWN outcome preserves follow-up loop continuity
When `ConfirmationResolver` returns `UNKNOWN`, the follow-up loop SHALL remain active. The microphone SHALL be re-armed, the confirmation question SHALL be re-spoken, and `currentTurnEndedInAskUser` SHALL remain `true`. The existing retry-on-silence mechanism, `CONFIRMATION_TIMEOUT_MS` timeout, and `MAX_VOICE_ROUNDS` circuit breaker SHALL apply across all rounds including UNKNOWN re-prompt rounds.

#### Scenario: UNKNOWN does not exit the follow-up loop
- **WHEN** `ConfirmationResolver` returns `UNKNOWN`
- **THEN** `currentTurnEndedInAskUser` is not set to `false`
- **AND** the microphone is re-armed for the next round
- **AND** the original confirmation question is re-spoken

#### Scenario: MAX_VOICE_ROUNDS applies across UNKNOWN rounds
- **WHEN** `UNKNOWN` is returned repeatedly until `rounds >= MAX_VOICE_ROUNDS`
- **THEN** the circuit-breaker fires with the terminal exit phrase
- **AND** task state is cleared via `conversationManager.clearVoiceTask()`
