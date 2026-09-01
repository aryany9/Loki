## Purpose
Durable on-device persistent memory store for user facts and preferences, injected into conversation system prompts and manageable via Settings.

## Requirements

### Requirement: Durable on-device memory store
The system SHALL persist user memory entries as a single JSON file under app-private storage with atomic writes and synchronized access, where each entry contains an id, text, creation timestamp, last-updated timestamp, and source (`MODEL_TOOL` or `USER_MANUAL`). Corrupt or missing store files SHALL degrade to an empty memory list without crashing.

#### Scenario: Memory survives process death
- **WHEN** a memory is saved and the app process is killed and restarted
- **THEN** the memory is present in the store

#### Scenario: Corrupt store degrades gracefully
- **WHEN** the memory file contains invalid JSON
- **THEN** the store returns an empty list and logs non-fatally

### Requirement: Memory is fully user-visible and manageable
The Settings screen SHALL provide a "What Loki remembers" section listing all memory entries with per-entry edit and delete, manual add, and clear-all (with confirmation). Nothing SHALL be stored as memory that the user cannot view and erase. Deletion SHALL be immediate and permanent.

#### Scenario: User deletes a memory
- **WHEN** the user deletes an entry in Settings
- **THEN** the entry is permanently removed from the store
- **AND** it no longer appears in injected prompts from the next conversation start

#### Scenario: User clears all memories
- **WHEN** the user confirms clear-all
- **THEN** the store is empty

### Requirement: Memory is injected into new conversations under a budget cap
`buildSystemPrompt` SHALL append stored memories as a "What you remember about the user" block, ordered most-recently-updated first, capped at 10 entries AND 800 characters (whichever is reached first). Memory content SHALL NOT displace the tool-signature or safety portions of the system prompt.

#### Scenario: New chat knows the user
- **WHEN** the user starts a new conversation after saving "My name is Arya"
- **THEN** the model's system prompt contains that memory and the assistant uses the name

#### Scenario: Budget cap respected
- **WHEN** the store holds 50 long memories
- **THEN** the injected block stops at 10 entries / 800 characters (most recent first)
- **AND** total system-prompt size remains within the existing token budget

### Requirement: Memory changes apply from the next conversation start
Because the native KV cache is initialized once per conversation, memory edits SHALL take effect on the next conversation start or conversation switch; no mid-conversation re-initialization is required.

#### Scenario: Mid-conversation edit
- **WHEN** the user deletes a memory while a chat is active
- **THEN** the active conversation is uninterrupted
- **AND** the memory is absent from the system prompt of the next conversation
