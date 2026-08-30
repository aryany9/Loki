# persist-conversation-history — Design

## Context

Today: `ChatViewModel` holds `MutableStateFlow<List<ChatMessage>>` (UI-only, lost on process death). `ConversationManager` owns a single `persistentChatContext` (`ConversationContext(maxTurns=10)`) shared by all chat sessions; voice sessions get an ephemeral `ConversationContext(maxTurns=1)`. `ConversationTurn` and `ToolResult` are already `@Serializable`. Project uses kotlinx-serialization-json, DataStore (for settings), Hilt/KSP. `ChatViewModel` already tracks an in-flight message id and finalizes on `Completed`.

## Goals / Non-Goals

**Goals:**
- Conversations survive restarts; multiple named conversations; startup restores most recent.
- Write-through persistence of completed turns (user + assistant + tool results).
- Zero new dependencies; simple, debuggable storage.
- `ConversationContext` prompt-budget trimming unchanged.

**Non-Goals:**
- Drawer/Settings UI (app-shell phase) — only a minimal new-chat affordance here.
- Full LLM KV-cache session replay: a loaded conversation re-seeds the *text* context; native KV cache starts fresh (engine rebuilds from prompt history).
- Search, sync, attachments, voice-session persistence.

## Decisions

### D1: Storage = kotlinx-serialization JSON files, one file per conversation
`ConversationRecord(id, title, createdAt, updatedAt, turns: List<ConversationTurn>)` serialized with the existing `Json { ignoreUnknownKeys }` instance to `filesDir/conversations/<id>.json`; an index (`meta` fields only) is kept in-memory by scanning/reading file headers — no Room, no DataStore schema churn.
*Why over Room:* single-user, small scale, `ConversationTurn` already serializable; Room adds schema/DAO/KSP complexity for no querying need beyond list+sort. *Why over DataStore Preferences:* multi-entity collections in Preferences are awkward; files map 1:1 to conversations and make delete = file delete. Revisit Room only if search/sync lands.

### D2: Write-through on turn completion, off the main path
`ConversationSession` already appends turns to `ConversationContext` as the pipeline completes them. The store is notified at the same points (via a listener injected into `ConversationSession`, or `ConversationManager` observing session completion): after each `Assistant`/`User`/`ToolExecutionResult` turn append, serialize asynchronously on `Dispatchers.IO`. Failures log and are non-fatal (chat continues unpersisted rather than crashing).
*Why not persist streaming partials:* final-only writes keep I/O low and data clean; a crash mid-generation loses that turn, which is acceptable.

### D3: Conversation switch = context swap + engine conversation reset
`ConversationManager` gains `createConversation(title)`, `listConversations()`, `loadConversation(id)`, `deleteConversation(id)`, `renameConversation(id, title)`. Loading swaps `persistentChatContext` to a new `ConversationContext` seeded with the stored turns and calls `llmEngine.startConversation(activeAgentConfig)` again (resetting the native KV cache — required since the engine's native `Conversation` cannot be rehydrated). Prompt budgeting still trims via existing `ConversationContext` logic.
*Alternative rejected:* one `ConversationContext` per session sharing the engine KV cache — not possible; the native cache is single-conversation.

### D4: `ChatViewModel` lifecycle mapping
- Startup: `listConversations()` → load most recent by `updatedAt`, else `createConversation()`; map stored `ConversationTurn`s → UI `ChatMessage`s (tool turns → `toolResult`/`toolName` fields; already mapped in Phase 2's model).
- On `Completed`/`Error` finalize, the store has already captured turns (write-through), so no extra UI save step.
- `clearChat()` becomes "new conversation" (old conversation file remains until deleted); delete/rename exposed for the shell phase but wired minimally now.

### D5: Title generation
Auto-title from the first user message (first ~40 chars, ellipsized) at creation of the first turn. Rename API exists for the drawer to call later.

## Risks / Trade-offs

- [File corruption on crash mid-write] → write-to-temp + atomic rename; `ignoreUnknownKeys` + per-file failure isolation (one bad file can't break listing).
- [Unbounded growth of conversation files] → full turn history retained; acceptable at expected scale. A retention cap (e.g. keep last N=50) can be added in the shell phase if needed.
- [Engine KV-cache reset on switch changes response quality for long restored contexts] → prompt re-seed covers content; KV-cache warmth is inherently unpersistable. Document as known limitation.
- [Concurrent writes from voice + chat sessions] → voice is ephemeral and never persists; single chat store path. Guard with a Mutex around file writes anyway.

## Migration Plan

Additive: store + manager methods + ViewModel wiring. No existing data to migrate (nothing persisted today). Rollback = revert; leftover files are harmless.

## Open Questions

None blocking. Retention cap deferred to app-shell phase.
