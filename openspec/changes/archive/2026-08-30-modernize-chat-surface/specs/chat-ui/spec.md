## MODIFIED Requirements

### Requirement: Text-based conversation input and output
The chat interface SHALL allow the user to send messages via text input and receive assistant responses displayed in a scrollable list. User messages SHALL render as end-aligned bubbles; assistant responses SHALL render as full-width, bubble-less messages with Markdown formatting per the `chat-message-rendering` capability, streaming progressively as tokens are generated.

#### Scenario: User sends text message
- **WHEN** the user types a prompt (e.g. "What time is it?" or "Turn on Bluetooth") and taps send
- **THEN** the message is added as a user bubble in the chat history
- **AND** the message text is forwarded to `ConversationManager.processUserInput(text)`
- **AND** the assistant response is appended to the chat history as a full-width assistant message and streams in as generation progresses

#### Scenario: Assistant response renders full-width
- **WHEN** the assistant produces a response (with or without markdown content)
- **THEN** the response renders across the full message width without a tinted bubble background, while user messages remain in end-aligned bubbles
