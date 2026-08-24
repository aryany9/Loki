### Requirement: Chat session provides persistent multi-turn memory
`ConversationManager.newChatSession()` SHALL return a `ConversationSession` backed by a `ConversationContext` that persists across multiple `processUtterance()` calls within the same chat UI session.

#### Scenario: User follow-up refers to previous turn
- **WHEN** a user sends "Who is Rahul?" and then sends "Call him"
- **THEN** the second turn's prompt includes the context of the first turn so the LLM can resolve "him" to "Rahul"

#### Scenario: Chat context survives multiple messages
- **WHEN** a user sends three consecutive messages in the chat UI
- **THEN** each subsequent prompt includes all prior turns up to the configured `maxTurns` budget

### Requirement: Voice session is always ephemeral
`ConversationManager.newVoiceSession()` SHALL return a `ConversationSession` backed by a fresh, empty `ConversationContext` with `maxTurns=1`. The session SHALL be discarded after the turn completes or errors.

#### Scenario: New voice invocation has no memory of previous sessions
- **WHEN** a voice session ends (dismissed or completed) and a new voice session is invoked
- **THEN** the new session's prompt contains no turns from any previous voice session

#### Scenario: Voice session does not share context with chat history
- **WHEN** the user has an active chat conversation and then invokes the voice assistant
- **THEN** the voice session prompt does not include any turns from the chat session

### Requirement: Session context is not shared between modes
The chat `ConversationSession` and any voice `ConversationSession` SHALL be independent objects with independent `ConversationContext` instances. Mutations to one SHALL NOT affect the other.

#### Scenario: Chat history unaffected by voice invocation
- **WHEN** a voice session fires `newVoiceSession()` while a chat session is active
- **THEN** the chat session's turn history is unchanged after the voice turn completes

### Requirement: Chat session can be explicitly cleared
The chat `ConversationSession` SHALL expose a `clear()` method. Calling it SHALL remove all accumulated turns from that session's `ConversationContext`.

#### Scenario: User clears chat history
- **WHEN** the user triggers "Clear Chat" in the UI
- **THEN** the chat session context is empty and the next `processUtterance()` starts with only the system prompt
