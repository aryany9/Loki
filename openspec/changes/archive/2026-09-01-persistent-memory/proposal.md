# Proposal: persistent-memory

## Why

Loki forgets the user the moment a conversation ends — `ConversationContext` trims turns per-session, and a new chat starts empty. Users expect a Gemini-like assistant that *remembers*: "I'm Aryanyadav, call me Arya", "my bike code is 4321", or "what did I ask you about the exam last week?" Two halves are missing: durable user facts that persist across all conversations, and a way for a fresh chat to reach prior chat history. Both must stay on-device and user-controlled — this is a privacy-first app, so memory must be visible and editable, never an opaque auto-learning blob.

## What Changes

- Add a **MemoryStore** (single JSON file under `filesDir/memory/`, atomic writes, Mutex — `ConversationStore` pattern) holding `MemoryEntry(id, text, createdAt, updatedAt, source)` entries.
- Add **two tools**: `remember_fact(content)` — the model stores a durable user fact (triggered by "remember that…", or when the user shares durable context); `search_chat_history(query)` — keyword search over all stored conversations' turns, returning matched snippets with conversation title + date so a fresh chat can reach prior chats.
- **Memory injection**: `ConversationSession.buildSystemPrompt` appends a "What you remember about the user" block (entries most-recent-first, capped at 10 entries / 800 chars ≈ 200 tokens of the 1500-token budget) so every new conversation starts knowing the user.
- **Settings UI — "What Loki remembers"**: a section in the Settings screen listing every memory with per-entry delete/edit, manual add, and clear-all. Nothing about the user is stored that the user cannot see and erase.
- Memory edits apply from the next conversation start (the native KV cache is initialized once per session); switching conversations re-seeds and re-injects via the existing `loadConversation` reset path.

## Capabilities

### New Capabilities
- `persistent-memory`: The durable, user-visible memory system — storage, tool capture, prompt injection with budget caps, and full user management (view/edit/delete/clear).

### Modified Capabilities
- `local-android-tools`: adds `remember_fact` and `search_chat_history` tools (registered set 15 → 17).
- `conversation-manager`: `buildSystemPrompt` gains the memory block injection; `ConversationStore` gains a keyword search API for history retrieval.
- `app-shell`: Settings screen gains the "What Loki remembers" management section.

## Impact

- **`core/conversation`**: `MemoryStore` (new file), `MemoryEntry`, `ConversationStore.searchTurns(query, limit)`, `ConversationSession.buildSystemPrompt` memory block.
- **`core/tools/local`**: `RememberFactTool`, `SearchChatHistoryTool` (the latter needs `ConversationStore` injected — via `AppModule`).
- **`core/ui`**: `SettingsViewModel` memory flows + CRUD; `SettingsScreen` memory section.
- No new dependencies. No manifest changes (memory is private app-internal storage). No voice-pipeline changes — both tools work identically in voice and chat through the existing tool loop.