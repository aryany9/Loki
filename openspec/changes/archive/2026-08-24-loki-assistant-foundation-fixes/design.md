## Context

Loki is an offline-first Android voice assistant powered by a local LLM (Qwen-4B via llama.cpp) and local STT (Whisper.cpp). Three foundational spikes validated the native stack. The current production application integrates those components but introduced several regressions from the spike behaviour: a shared singleton `ConversationManager` never clears context between sessions; tool descriptions are absent from the LLM system prompt (only GBNF grammar constrains output format, not model reasoning); the app lacks a working theme system; permissions required by tools (`CALL_PHONE`, `READ_CONTACTS`) are missing from the manifest; and pipeline observability is essentially absent.

## Goals / Non-Goals

**Goals:**
- Fix the repeating-answer and wrong-tool-selection bug by scoping conversation context to its mode (chat vs voice).
- Make the LLM reason correctly about tools by embedding tool descriptions in the system prompt.
- Make it syntactically impossible for the LLM to select a tool whose required permission is missing.
- Provide a clear, actionable UX response when a tool cannot run due to a missing permission.
- Establish end-to-end pipeline observability with correlation IDs.
- Deliver a working Material 3 light/dark/system theme with DataStore persistence.
- Add a first-run setup screen and a persistent permissions management screen.

**Non-Goals:**
- Multi-model support or model switching UI (separate change).
- Whisper language switching in UI (configuration only in this change).
- WhatsApp integration, online tools, or web search.
- VoiceInteractionService lock-screen re-architecture.
- Streaming token output in the chat UI.

## Decisions

### D1 — ConversationSession as a scoped unit, not a global singleton

**Decision:** Introduce `ConversationSession` as the unit of a single assistant interaction. `ConversationManager` becomes a factory that produces sessions via `newChatSession()` (returns a session backed by a persistent `ConversationContext` that lives on the `ChatViewModel`) and `newVoiceSession()` (returns a fresh, throwaway `ConversationContext` each invocation).

**Why not just call `clear()` on the shared context?**
`clear()` is a race condition — if a background voice session fires `clear()` while the chat UI is in mid-turn, the chat history is destroyed. Separate session objects remove the shared mutable state entirely.

**Voice session lifecycle:**
```
AssistantSession.startTurn()
    │
    └─► conversationManager.newVoiceSession()
                │  creates ephemeral ConversationSession
                │  (ConversationContext with maxTurns=1)
                ▼
           session.processUtterance(transcript)
                │
                └─► session discarded after turn completes or errors
```

**Chat session lifecycle:**
```
ChatViewModel init
    │
    └─► conversationManager.newChatSession()
                │  creates ConversationSession backed by a
                │  persistent ConversationContext held on ViewModel
                ▼
           session.processUtterance(userInput)
                │  context survives across multiple sendMessage() calls
                └─► context.clear() only on explicit user "Clear chat"
```

### D2 — Tool descriptions in system prompt, not just in grammar

**Decision:** The system prompt is rebuilt each turn to include:
1. A list of **available tools** with name, one-line description, and parameter signatures.
2. A list of **disabled tools** (missing permission) with the reason.

The GBNF grammar still enforces valid JSON syntax. The prompt text provides semantic reasoning context. These are complementary, not alternatives.

**Prompt structure:**
```
<|im_start|>system
You are Loki, a private offline Android assistant running on the user's device.

Available tools (you MUST use one of these):
- get_current_time: Get the current time and date from the device clock.
- get_battery_status: Get the current battery level and charging status.
- lookup_contact(query: string): Search device contacts by name.
- set_timer(seconds: number, message?: string): Set a countdown timer.
- set_alarm(hour: number, minute: number, label?: string): Set an alarm.
- media_control(action: string): Control media playback (play/pause/next/previous).
- open_app(name: string): Open an installed application by name.

Disabled tools (permission not granted — respond with a helpful explanation):
- call_contact: Requires CALL_PHONE permission (not yet granted).

Respond ONLY with valid JSON: {"tool": "name", "arguments": {...}} or {"response": "text"}.
<|im_end|>
```

**Why rebuild each turn?** Permission state can change at runtime (user grants during session). The grammar must also be rebuilt (or re-fetched from cache) when permission state changes. Rebuilding the prompt is cheap; grammar compilation is cached.

### D3 — Grammar caching keyed on tool-name set

**Decision:** `GrammarBuilder` maintains a `@Volatile` cache keyed on the sorted set of tool names in the grammar. If the set is unchanged between calls, the cached GBNF string is returned without re-invoking the native `nativeJsonSchemaToGrammar()`.

Permission state changes invalidate the cache since the tool-name set changes.

### D4 — PermissionManager: three-state model

**Decision:** A new `PermissionManager` (Hilt `@Singleton`) wraps `ContextCompat.checkSelfPermission()` and `ActivityCompat.shouldShowRequestPermissionRationale()` to produce three states:

| State | Meaning | UI action |
|-------|---------|-----------|
| `GRANTED` | Permission available | Execute tool normally |
| `REQUESTABLE` | Not granted, Android will show the dialog | Show rationale → `requestPermissions()` |
| `PERMANENTLY_DENIED` | User ticked "Don't ask again" | Show "Open App Settings" button |

`ToolRegistry.execute()` returns a `ToolExecutionResult` sealed type that includes `PermissionRequired(permission, state)` alongside `Success` and `Failure`.

### D5 — TurnLogger with UUID correlation

**Decision:** A lightweight `TurnLogger` object generates a random UUID for each assistant turn and emits structured `Log.i` / `Log.d` entries under the tag `LokiTurn`. Prompt content is gated on `BuildConfig.DEBUG` only.

```kotlin
// Example output (debug build)
I/LokiTurn: [abc123] source=VOICE
I/LokiTurn: [abc123] transcript="Call Mom"
I/LokiTurn: [abc123] available_tools=7 disabled_tools=1
D/LokiTurn: [abc123] prompt=<full prompt text>   ← debug only
I/LokiTurn: [abc123] llm_output={"tool":"call_contact","arguments":{...}}
I/LokiTurn: [abc123] parse=ToolCall(tool=call_contact)
I/LokiTurn: [abc123] permission_check=CALL_PHONE→REQUESTABLE
I/LokiTurn: [abc123] final_response="I need the Call Phone permission to make calls."
```

### D6 — Theme: Material 3, DataStore persistence

**Decision:** Introduce `LokiTheme.kt` with `LokiDarkColorScheme` and `LokiLightColorScheme` (Material 3 `darkColorScheme` / `lightColorScheme`). `ThemeRepository` reads/writes a `ThemeMode` enum (`DARK`, `LIGHT`, `SYSTEM`) to `DataStore<Preferences>`.

`MainActivity` collects `ThemeMode` as a `StateFlow` and passes it into a root `LokiTheme { }` composable that wraps all content. All `ChatScreen`, `VoiceSessionOverlay`, and new screens use `MaterialTheme.colorScheme.*` tokens — no hardcoded hex colors.

The XML `themes.xml` parent is updated from `android:Theme.Material.Light.NoActionBar` to `Theme.Material3` so the system chrome (status bar, nav bar) correctly follows the selected mode.

### D7 — Setup / Permissions Screen placement

**Decision:** Two entry points:
1. **First-run onboarding**: On a fresh install, `MainActivity` checks a `DataStore` boolean `first_run_complete`. If false, it navigates to `SetupScreen` before showing `ChatScreen`. `SetupScreen` requests `RECORD_AUDIO` (mandatory) and `POST_NOTIFICATIONS`, and explains each tool's optional permission (contacts, calls).
2. **Persistent access**: A "Permissions" item in the settings/nav area of `ChatScreen` opens `PermissionsScreen` at any time.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| System prompt growing too long with many tools | Limit tool description to name + one-line summary; parameter signatures are compact. Monitor token count via `TurnLogger`. Cap at 20 tools before redesign is needed. |
| Grammar cache invalidation on permission grant mid-session | Cache key includes sorted tool names; any change (grant or revoke) produces a different key → cache miss → fresh grammar compiled. |
| `newVoiceSession()` creates a new object each invocation — GC pressure | `ConversationSession` is lightweight (wraps a `ConversationContext`). No native resources held. GC impact is negligible. |
| DataStore async read causes theme flash on cold start | Use `runBlocking { themeRepo.getThemeMode() }` for the initial synchronous read in `MainActivity.onCreate()`, then switch to Flow collection for subsequent changes. |
| Double-cancel in `onHide` + `onDestroy` for voice session | Guard `nativeCancel()` with a `@Volatile cancelled: Boolean` flag in `LlamaCppLlmEngine`; subsequent cancels are no-ops. |

## Open Questions

- Should `ConversationSession` for chat be cleared only on explicit user action ("Clear Chat" button), or also on app process death/restart? **Proposed:** only explicit user action; DataStore-backed persistence of chat history is a future feature.
- Should the system prompt list tool *parameters* or just names and descriptions? **Proposed:** one-line description + parameter names only (not types) to stay compact.
