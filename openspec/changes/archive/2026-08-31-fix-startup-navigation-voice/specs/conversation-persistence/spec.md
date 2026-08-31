## MODIFIED REQUIREMENTS

### Requirement: Chat restores the most recent conversation at startup
When the chat screen initializes, the system SHALL present the new-chat home state (empty conversation) rather than auto-opening an existing or newly created conversation. No conversation SHALL be created on startup; the active conversation identity SHALL be assigned lazily on the user's first message. Switching conversations (from the drawer recents) SHALL reset the LLM engine's native conversation state and re-seed the text context from stored turns. Existing stored conversations SHALL remain listed in the drawer recents.

#### Scenario: Fresh install shows home, no eager creation
- **WHEN** the app starts for the first time with no stored conversations
- **THEN** the chat surface shows the empty home state with greeting and suggestion chips, and NO conversation record is created in storage until the user sends a first message

#### Scenario: Restart shows a fresh chat, recents preserved
- **WHEN** the app restarts with stored conversations
- **THEN** the chat surface opens on the empty home state (not the most recent conversation), and all stored conversations remain available in the drawer recents

#### Scenario: First message creates the conversation
- **WHEN** the user sends their first message after launch (from the home state)
- **THEN** a new conversation is created and persisted, and subsequent turns append to it