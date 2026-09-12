## MODIFIED Requirements

### Requirement: Unresolved confirmations time out and cancel safely
A pending confirmation SHALL auto-cancel after a bounded timeout (the shared
`CONFIRMATION_TIMEOUT_MS`) on every surface, including the voice overlay, producing the
same denial tool-result as an explicit rejection so the model can respond
conversationally. Cancelling generation SHALL also resolve any pending confirmation as
denied. The voice path SHALL NOT substitute a hardcoded "cancelled" message for the
denial tool-result.

#### Scenario: No response before timeout
- **WHEN** the confirmation timeout elapses with no verdict
- **THEN** the pending confirmation is cancelled
- **AND** a tool-result turn stating the action was cancelled is appended

#### Scenario: Generation cancelled while awaiting
- **WHEN** the user cancels generation while a confirmation is pending
- **THEN** the pending confirmation is resolved as denied
- **AND** no tool execution occurs afterwards

#### Scenario: Voice timeout feeds the denial result to the model
- **WHEN** the voice confirmation listen attempts fail or the timeout elapses
- **THEN** the gate resolves as denied with a denial tool-result in the conversation
  context
- **AND** the model replies conversationally instead of a hardcoded "cancelled" terminal
