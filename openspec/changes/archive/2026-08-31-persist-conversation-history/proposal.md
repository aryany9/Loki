# persist-conversation-history

## Why

Chat history lives only in memory (`ChatViewModel` StateFlow + `ConversationContext` max 10 turns) and vanishes on process death — the `chat-ui` spec explicitly deferred persistence "to a future change". Durable, multi-conversation history is the prerequisite for the Gemini-style nav drawer with "recents" planned in the app-shell phase, and it protects users from losing long conversations.

## What Changes

- Add a `ConversationStore` in `core/conversation`: durable storage of conversations (id, title, timestamps, full turn list) as kotlinx-serialization JSON files in app-private storage — `ConversationTurn` is already `@Serializable`, so no new dependencies and no Room/DataStore schema machinery.
- `ConversationManager` gains multi-conversation support: create/list/load/delete conversations; the persistent chat context is backed by the store (write-through on each completed turn).
- `ChatViewModel` loads the most recent conversation at startup (or a fresh one), persists turns as they complete, and exposes conversation list + new-chat/switch/delete actions.
- Existing `ConversationContext` trimming behavior is unchanged — it still trims for LLM prompt budgeting; the store keeps full history.
- Voice sessions remain ephemeral (not persisted), matching current scoping.
- Out of scope: drawer/settings UI (app-shell phase), cloud sync, search within history, media attachments.

## Capabilities

### New Capabilities
- `conversation-persistence`: Durable storage of named conversations and their full turn history; list/load/delete/rename; write-through persistence during chat; startup restore.

### Modified Capabilities
- `chat-ui`: The "In-memory session history in chat mode" requirement is replaced — history persists across app restarts, and the chat screen operates on a selected stored conversation.

## Impact

- **Code**: new `ConversationStore.kt` (+ `ConversationRecord` model) in `core/conversation`; `ConversationManager.kt` (conversation registry + context backing); `ChatViewModel.kt` (startup load, write-through, list/new/switch/delete); `ChatScreen.kt` (minimal: new-chat affordance only — full drawer later).
- **Dependencies**: none — reuses existing `kotlinx-serialization-json`, `DataStore` untouched.
- **Data**: JSON files under `context.filesDir/conversations/`. No migration needed (nothing persisted today).
- **Specs**: new `conversation-persistence` spec; `chat-ui` in-memory requirement MODIFIED.
- **Risk surface**: file I/O on Dispatchers.IO only; engine KV cache reset on conversation switch (see design D3).
