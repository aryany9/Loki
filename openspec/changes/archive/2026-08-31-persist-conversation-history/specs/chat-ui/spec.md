## MODIFIED Requirements

### Requirement: Persistent session history in chat mode
The chat interface SHALL operate on a durable, stored conversation: history SHALL survive app restarts via the `conversation-persistence` capability, the most recent conversation SHALL be restored at startup, and the user SHALL be able to start a new conversation from the chat surface. In-memory-only session history is no longer sufficient.

#### Scenario: Conversation builds across multiple turns
- **WHEN** the user interacts with Loki across multiple back-and-forth messages in the chat screen
- **THEN** previous messages remain visible and scrollable in the chat history list during the current activity session

#### Scenario: History survives restart
- **WHEN** the user completes exchanges, force-closes the app, and reopens it
- **THEN** the previous conversation's messages are restored in the chat screen and new messages append to the same conversation
