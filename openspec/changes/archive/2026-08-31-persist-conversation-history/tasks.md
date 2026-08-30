## 1. ConversationStore

- [x] 1.1 Add `ConversationRecord` (id, title, createdAt, updatedAt, turns) and `ConversationStore` (JSON files under `filesDir/conversations/`, atomic writes via temp+rename, Mutex around writes, `Dispatchers.IO`) with `create/list/load/delete/rename/appendTurn` APIs — no new dependencies
- [x] 1.2 Unit test store CRUD + round-trip serialization of `ConversationTurn`s (incl. ToolExecutionResult) using a temp dir; test atomicity by verifying list stays intact when one file is corrupt

## 2. ConversationManager integration

- [x] 2.1 Add conversation registry methods (`createConversation`, `listConversations`, `loadConversation`, `deleteConversation`, `renameConversation`, `currentConversationId`); loading swaps `persistentChatContext` with a context seeded from stored turns and calls `llmEngine.startConversation(activeAgentConfig)` to reset the native KV cache
- [x] 2.2 Write-through: persist user + assistant + tool-result turns as they complete (turn append hook in session path); auto-title from first user message (~40 chars); all I/O off main thread, failures logged non-fatally
- [x] 2.3 Unit test: switch between two conversations and assert context seeding + engine conversation reset invoked

## 3. ChatViewModel + minimal UI

- [x] 3.1 Startup: restore most recent conversation (or create new); map stored `ConversationTurn`s to UI `ChatMessage`s; expose `newConversation()` (replaces `clearChat()` semantics), `deleteConversation(id)`, `renameConversation(id, title)`
- [x] 3.2 Add a minimal "new chat" affordance in the ChatScreen top bar (full drawer deferred to app-shell phase)
- [x] 3.3 Keep voice sessions ephemeral — confirm voice path neither reads nor writes the store

## 4. Validation

- [x] 4.1 `./gradlew test :app:assembleDebug` passes
- [x] 4.2 Manual: complete exchanges → force-stop → relaunch → history restored and continues in the same conversation; new chat starts fresh; old conversation still listed
- [x] 4.3 Manual: kill app mid-generation → relaunch shows persisted turns only (no partial), app stable
- [x] 4.4 Run `openspec validate persist-conversation-history` and confirm all tasks complete
