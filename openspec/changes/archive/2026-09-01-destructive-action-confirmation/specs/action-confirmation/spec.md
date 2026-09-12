# action-confirmation — Delta

## ADDED Requirements

### Requirement: Tools declare destructive actions requiring confirmation
A tool that performs a destructive or irreversible user-facing action SHALL declare `requiresConfirmation = true` and SHALL provide a natural-language `describeAction(arguments)` repeat-back string identifying the concrete target (e.g. contact name and phone number). Tools without the declaration SHALL execute immediately as before.

#### Scenario: Gated tool is invoked
- **WHEN** the model emits a tool call for a tool with `requiresConfirmation = true`
- **THEN** the tool is NOT executed
- **AND** the conversation layer emits a confirmation-required event containing the tool name and the repeat-back string

#### Scenario: Ungated tool is invoked
- **WHEN** the model emits a tool call for a tool with `requiresConfirmation = false`
- **THEN** the tool executes immediately with no confirmation step

### Requirement: The conversation loop awaits an explicit verdict
When a confirmation is required, the conversation loop SHALL suspend before tool execution, emit the repeat-back to the active surface (chat and/or voice overlay), and await a verdict through a single `respondToConfirmation(accepted: Boolean)` entry point. Only one confirmation SHALL be pending at a time.

#### Scenario: User confirms
- **WHEN** the user accepts the pending confirmation
- **THEN** the tool executes with its original arguments
- **AND** execution continues exactly as an ungated call would

#### Scenario: User denies
- **WHEN** the user rejects the pending confirmation
- **THEN** no tool execution occurs
- **AND** a tool-result turn stating the user declined is appended so the model can respond conversationally

### Requirement: Unresolved confirmations time out and cancel safely
A pending confirmation SHALL auto-cancel after a bounded timeout, producing the same denial turn as an explicit rejection. Cancelling generation SHALL also resolve any pending confirmation as denied.

#### Scenario: No response before timeout
- **WHEN** the confirmation timeout elapses with no verdict
- **THEN** the pending confirmation is cancelled
- **AND** a tool-result turn stating the action was cancelled is appended

#### Scenario: Generation cancelled while awaiting
- **WHEN** the user cancels generation while a confirmation is pending
- **THEN** the pending confirmation is resolved as denied
- **AND** no tool execution occurs afterwards