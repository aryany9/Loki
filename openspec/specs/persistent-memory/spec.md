## Purpose
Durable on-device persistent memory store for user facts and preferences, injected into conversation system prompts and manageable via a dedicated Memory screen.
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
A dedicated Memory screen (accessible from the navigation drawer) SHALL provide a "What Loki remembers" view listing all memory entries with per-entry edit and delete, manual add, and clear-all (with confirmation). Nothing SHALL be stored as memory that the user cannot view and erase. Deletion SHALL be immediate and permanent.

#### Scenario: User deletes a memory
- **WHEN** the user deletes an entry in the Memory screen
- **THEN** the entry is permanently removed from the store
- **AND** it no longer appears in injected prompts from the next conversation start

#### Scenario: User clears all memories
- **WHEN** the user confirms clear-all
- **THEN** the store is empty

### Requirement: Memory is injected into new conversations under a budget cap
`buildSystemPrompt` SHALL retrieve applicable memories via `MemoryStore.getMemoriesFor(mode, budget)` and append them as a "What you remember about the user" block, ordered most-recently-updated first. The injected block SHALL strictly observe the dynamic character budget allocated to memory after reserving tokens for output, system foundation, active tool schemas, and conversation history. Memory content SHALL NOT displace safety, domain, or tool protocol portions of the prompt.

#### Scenario: New chat knows the user
- **WHEN** the user starts a new conversation after saving "My name is Arya"
- **THEN** the model's system prompt contains that memory and the assistant uses the name

#### Scenario: Budget cap respected
- **WHEN** the store holds 50 long memories
- **THEN** `getMemoriesFor()` truncates entries to fit within the dynamically calculated memory budget
- **AND** total system-prompt size remains strictly within KV cache capacity

### Requirement: Memory changes apply from the next conversation start
Because the native KV cache is initialized once per conversation, memory edits SHALL take effect on the next conversation start or conversation switch; no mid-conversation re-initialization is required.

#### Scenario: Mid-conversation edit
- **WHEN** the user deletes a memory while a chat is active
- **THEN** the active conversation is uninterrupted
- **AND** the memory is absent from the system prompt of the next conversation

### Requirement: Scoped Memory Storage and Retrieval
Each `MemoryEntry` SHALL carry a `MemoryScope` (`GLOBAL`, `VOICE`, or `CHAT`). `MemoryStore.getMemoriesFor(mode, budget)` SHALL encapsulate visibility matching: `GLOBAL` memories are returned for all modes, `VOICE` memories are returned only when `mode == ConversationMode.VOICE`, and `CHAT` memories are returned only when `mode == ConversationMode.CHAT`.

#### Scenario: Voice-specific memory hidden from Chat
- **WHEN** a memory entry has `scope = MemoryScope.VOICE`
- **THEN** `getMemoriesFor(ConversationMode.CHAT, budget)` omits the entry
- **AND** `getMemoriesFor(ConversationMode.VOICE, budget)` includes the entry

#### Scenario: Global memory visible to all modes
- **WHEN** a memory entry has `scope = MemoryScope.GLOBAL`
- **THEN** both Voice and Chat sessions receive the memory within their allocated budgets

