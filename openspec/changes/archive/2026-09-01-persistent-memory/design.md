# Design: persistent-memory

## Context

`ConversationSession.buildSystemPrompt` (line 333) assembles the prompt from a fixed intro + custom instruction + tool signatures; it runs once per conversation (`startConversation` fires when `turns <= 1`, line 118). `ConversationContext` budgets 1500 tokens / 10 turns and trims oldest-first — nothing survives across conversations. `ConversationStore` persists every turn durably but exposes no search. `SettingsViewModel` currently owns theme + model state only. `DefaultLocalTools` registers 15 tools. The KV-cache constraint from `persist-conversation-history` stands: the native cache re-initializes on `startConversation`, so prompt content (including memory) is naturally refreshed on conversation switch.

## Goals / Non-Goals

- **Goals**: durable user facts surviving across conversations; fresh chats able to reach prior history; full user visibility/edit control; hard prompt-budget caps so memory can never crowd out working context.
- **Non-Goals**: embedding-based semantic retrieval (keyword search v1; embeddings need an on-device model — future); auto-extraction of memories from arbitrary conversation content (v1 stores only what the model explicitly saves via `remember_fact` or the user types in Settings); memory sync/backup across devices; per-conversation scoped memory.

## Decisions

### D1: Single-file MemoryStore mirroring ConversationStore's guarantees
`filesDir/memory/memories.json` holds a `List<MemoryEntry>` (`id` UUID, `text`, `createdAtEpochMs`, `updatedAtEpochMs`, `source`: `MODEL_TOOL` / `USER_MANUAL`). Atomic temp+rename writes, `Mutex`, `Dispatchers.IO`, non-fatal failure logging — the exact `ConversationStore` pattern (its `loadLocked`/`saveLocked` shape is copied, not shared, to keep the two stores independently evolvable). API: `getAll()`, `add(text, source)`, `update(id, text)`, `delete(id)`, `clear()`.
*Alternative rejected:* Room — same reasoning as `persist-conversation-history` D1; a flat JSON list at expected scale (tens of entries) needs no schema machinery.

### D2: Capture is explicit-only in v1
Memories enter via (a) the model calling `remember_fact` — the system prompt instructs it to use this when the user says "remember…", "my name is…", or shares durable personal context — or (b) manual entry in Settings. *Auto-extraction rejected for v1:* a small on-device model silently memorizing conversational content is both a quality risk (storing garbage, missing intent) and a trust risk in a privacy-first app; the system-prompt instruction gives the model a reliable, auditable capture path. Auto-extraction is a future change if explicit capture proves too sparse.


### D4: History retrieval = keyword search over stored turns
`ConversationStore.searchTurns(query, limit=5)`: case-insensitive substring match across `ConversationTurn.User`/`Assistant` texts (loaded per file, corrupt files skipped like `listConversations`), returning `{conversationId, conversationTitle, snippet, dateEpochMs}`. The `search_chat_history` tool wraps it and renders results as `"<title> (<date>): …snippet…"` lines. Substring-match v1 is honest about its limits (no ranking, no synonyms) — but is fully offline, dependency-free, and matches how small context windows actually get used (the model re-asks or the user refines).
*Alternative rejected:* on-device embeddings model — new dependency, new download, new runtime for v1; noted as future upgrade path.

### D5: Settings management is full CRUD, colocated with theme/model sections
`SettingsViewModel` gains `memories: StateFlow<List<MemoryEntry>>` + `addMemory`, `updateMemory`, `deleteMemory`, `clearMemories`. `SettingsScreen` gains a "What Loki remembers" card: list with per-entry overflow (edit/delete), an add field, and clear-all with a confirm dialog. A subtitle states "Loki uses these in every new chat." Deletion is immediate and permanent (no trash) — simplest honest model for local data.

### D6: Tool registration and dedupe behavior
Both tools register via `DefaultLocalTools` (15 → 17). `SearchChatHistoryTool` needs the store: `AppModule` provides it (it already provides `ConversationStore` for `ConversationManager`). `remember_fact` dedupes: if an entry with identical trimmed text exists, it updates `updatedAt` instead of duplicating. Neither tool is confirmation-gated (writing local memory is reversible and user-visible — the gate spec's "destructive/irreversible" bar is not met).

## Risks / Trade-offs

- **Prompt drift**: memory block baked at conversation start can go stale mid-chat if the user edits Settings — mitigated by the "applies to new chats" subtitle; staleness is cosmetic, not dangerous.
- **Model over-calls `remember_fact`** → system prompt constrains it to *durable* facts ("preferences, identity, recurring context — not transient requests"); duplicates dedupe via D6; user can delete noise from Settings.
- **Keyword search misses paraphrases** → acceptable v1; the tool returns up to 5 snippets and the model can retry with different terms; embeddings listed as future path.
- **Memory file corruption** → same isolation pattern as conversations: load failure logs and returns empty list (never crashes), and `clear()` + re-add restores a clean state.

## Migration Plan

No migration: the store file doesn't exist until the first memory is added. Tools are additive registrations; existing 15 tools untouched. Settings section appears whether or not memories exist (empty state: "Nothing remembered yet").

## Open Questions

- None. Semantic retrieval and auto-extraction are the two named future upgrades if v1 proves too limited.