## MODIFIED Requirements

### Requirement: Memory is injected into new conversations under a budget cap
`buildSystemPrompt` SHALL retrieve applicable memories via `MemoryStore.getMemoriesFor(mode, budget)` and append them as a "What you remember about the user" block, ordered most-recently-updated first. The injected block SHALL strictly observe the dynamic character budget allocated to memory after reserving tokens for output, system foundation, active tool schemas, and conversation history. Memory content SHALL NOT displace safety, domain, or tool protocol portions of the prompt.

#### Scenario: New chat knows the user
- **WHEN** the user starts a new conversation after saving "My name is Arya"
- **THEN** the model's system prompt contains that memory and the assistant uses the name

#### Scenario: Budget cap respected
- **WHEN** the store holds 50 long memories
- **THEN** `getMemoriesFor()` truncates entries to fit within the dynamically calculated memory budget
- **AND** total system-prompt size remains strictly within KV cache capacity

## ADDED Requirements

### Requirement: Scoped Memory Storage and Retrieval
Each `MemoryEntry` SHALL carry a `MemoryScope` (`GLOBAL`, `VOICE`, or `CHAT`). `MemoryStore.getMemoriesFor(mode, budget)` SHALL encapsulate visibility matching: `GLOBAL` memories are returned for all modes, `VOICE` memories are returned only when `mode == ConversationMode.VOICE`, and `CHAT` memories are returned only when `mode == ConversationMode.CHAT`.

#### Scenario: Voice-specific memory hidden from Chat
- **WHEN** a memory entry has `scope = MemoryScope.VOICE`
- **THEN** `getMemoriesFor(ConversationMode.CHAT, budget)` omits the entry
- **AND** `getMemoriesFor(ConversationMode.VOICE, budget)` includes the entry

#### Scenario: Global memory visible to all modes
- **WHEN** a memory entry has `scope = MemoryScope.GLOBAL`
- **THEN** both Voice and Chat sessions receive the memory within their allocated budgets
