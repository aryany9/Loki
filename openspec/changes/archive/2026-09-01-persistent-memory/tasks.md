## 1. MemoryStore

- [x] 1.1 Create `MemoryEntry` (`id`, `text`, `createdAtEpochMs`, `updatedAtEpochMs`, `source` enum `MODEL_TOOL`/`USER_MANUAL`) and `MemoryStore` (`filesDir/memory/memories.json`, atomic temp+rename, Mutex, `Dispatchers.IO`, non-fatal corrupt-file handling) with `getAll/add/update/delete/clear` — ConversationStore pattern, no new dependencies
- [x] 1.2 Unit test CRUD round-trip, duplicate-add dedupe (refresh timestamp, no second entry), corrupt-file → empty list, clear

## 2. History search

- [x] 2.1 Add `ConversationStore.searchTurns(query, limit=5)`: case-insensitive substring over User/Assistant turn texts across all conversation files, corrupt files skipped and logged, results as `(conversationId, conversationTitle, snippet, dateEpochMs)`
- [x] 2.2 Unit test: matches across multiple conversations, corrupt file skipped, empty query/no-match → empty list

## 3. Tools

- [x] 3.1 `RememberFactTool` (`remember_fact`, content param) → `MemoryStore.add(source=MODEL_TOOL)` with dedupe; `SearchChatHistoryTool` (`search_chat_history`, query param) → `store.searchTurns` rendered as `"<title> (<date>): <snippet>"` lines
- [x] 3.2 Register both in `DefaultLocalTools` (15 → 17); update `LocalToolsTest` count; provide `MemoryStore`/store wiring in `AppModule` for `SearchChatHistoryTool`; assert neither tool is confirmation-gated
- [x] 3.3 Unit tests: tool arg validation, remember→store round-trip, search tool result formatting

## 4. Prompt injection

- [x] 4.1 `ConversationSession.buildSystemPrompt`: append "What you remember about the user" block — entries sorted `updatedAt` desc, capped at 10 entries AND 800 chars, plain-text lines; extend the capture guidance ("use remember_fact for durable facts") in the tool-signature section
- [x] 4.2 Unit test: injection caps (11+ entries → 10; >800 chars truncated at entry boundary); empty store → no block; memory content appears in prompt for a new conversation

## 5. Settings management UI

- [x] 5.1 `SettingsViewModel`: `memories: StateFlow<List<MemoryEntry>>` + `addMemory/updateMemory/deleteMemory/clearMemories`; unit test CRUD flows update the flow
- [x] 5.2 `SettingsScreen`: "What Loki remembers" card — list, add field, per-entry edit/delete, clear-all with confirm dialog, "applies to new chats" subtitle, "Nothing remembered yet" empty state; theme tokens only
- [x] 5.3 Manual check: add/edit/delete/clear-all flows; memory set in Settings appears in the next new chat's context ("call me Arya" → assistant uses Arya)

## 6. Validation

- [x] 6.1 `./gradlew test :app:assembleDebug` passes; all existing suites green
- [x] 6.2 Manual matrix: voice "remember that…" → tool card → fact stored → visible in Settings; new-chat "what's my bike code?" answered from memory; `search_chat_history` from a fresh chat finds a prior-conversation topic; deletion reflected in next chat
- [x] 6.3 `openspec validate persistent-memory` passes; tick all tasks