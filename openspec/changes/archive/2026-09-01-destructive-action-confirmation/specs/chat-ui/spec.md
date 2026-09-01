# chat-ui — Delta

## ADDED Requirements

### Requirement: Chat renders a confirmation card for pending destructive actions
When a confirmation is pending in a chat session, the chat surface SHALL display a card above the composer containing the repeat-back text and Confirm / Cancel actions. Selecting either action SHALL resolve the pending confirmation and dismiss the card. Cancelling generation SHALL dismiss the card as a denial.

#### Scenario: Confirmation card accepted
- **WHEN** the user taps Confirm on the card
- **THEN** the pending tool executes and the card is dismissed
- **AND** the normal tool-result and assistant flow continues

#### Scenario: Confirmation card cancelled
- **WHEN** the user taps Cancel on the card
- **THEN** no tool execution occurs and the card is dismissed
- **AND** the assistant responds based on the denial tool-result turn
