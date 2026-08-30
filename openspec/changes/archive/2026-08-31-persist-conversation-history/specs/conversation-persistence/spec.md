## ADDED Requirements

### Requirement: Conversations are durably stored
Each conversation (id, title, creation/update timestamps, and its full turn history including user messages, assistant responses, and tool execution results) SHALL be persisted to app-private storage as JSON via kotlinx-serialization. Writes SHALL occur asynchronously off the main thread and SHALL be atomic (write-to-temp + rename) so a crash cannot corrupt existing data.

#### Scenario: Turn survives process death
- **WHEN** the user completes at least one full exchange in a conversation and the app process is killed and restarted
- **THEN** the conversation, including that exchange, is available again with all turn content intact

#### Scenario: Storage failure is non-fatal
- **WHEN** a persistence write fails (I/O error)
- **THEN** the failure is logged, the chat continues to function, and no crash occurs

### Requirement: Multi-conversation management
The system SHALL support creating, listing (sorted by most recently updated), loading, renaming, and deleting conversations. Deleting a conversation SHALL remove its stored data. Voice sessions SHALL remain ephemeral and SHALL NOT be persisted.

#### Scenario: List conversations
- **WHEN** the app requests the conversation list
- **THEN** all stored conversations are returned ordered by most recently updated first

#### Scenario: Delete removes data
- **WHEN** a conversation is deleted
- **THEN** its stored file is removed and it no longer appears in listings

### Requirement: Chat restores the most recent conversation at startup
When the chat screen initializes, the system SHALL load the most recently updated conversation (seeded into the conversation context and rendered in the UI), or create a new conversation if none exists. Switching conversations SHALL reset the LLM engine's native conversation state and re-seed the text context from stored turns.

#### Scenario: Fresh install
- **WHEN** the app starts for the first time with no stored conversations
- **THEN** a new empty conversation is created and the chat screen opens on it

#### Scenario: Restore on restart
- **WHEN** the app restarts with stored conversations
- **THEN** the most recent conversation's history is rendered in the chat screen and subsequent turns append to it

### Requirement: Conversation titles
A conversation SHALL be auto-titled from its first user message (truncated); the system SHALL expose a rename operation for later UI. Users SHALL be able to start a new conversation from the chat surface.

#### Scenario: Auto-title
- **WHEN** the first user turn is persisted in a new conversation
- **THEN** the conversation title is derived from that message (truncated to ~40 characters)
