## Why

The Loki assistant pipeline has several foundational defects that make it non-functional as a real voice assistant: the LLM is consistently selecting `get_current_time` regardless of user intent due to missing tool descriptions in the system prompt and stale conversation history bleeding across voice sessions; there is no observability into the pipeline making debugging impossible; tools with missing Android permissions are silently failing with a generic "go to Settings" message; the app has no light/dark theme system; and the first-run user experience does not request or explain required permissions.

## What Changes

- **Conversation session scoping**: Introduce `ConversationSession` to replace direct use of `ConversationContext` on `ConversationManager`, with two distinct modes — `newChatSession()` (persists multi-turn history for the chat UI) and `newVoiceSession()` (always fresh, ephemeral, no cross-session memory for voice).
- **System prompt with tool descriptions**: The LLM prompt now includes human-readable descriptions of all available (permitted) tools and explicitly lists disabled tools with their missing permission, so the model can produce a helpful response when a required tool is unavailable.
- **Permission-aware grammar filtering**: Tools whose required Android permissions are not granted are excluded from the GBNF grammar at generation time, making it syntactically impossible for the LLM to select an unexecutable tool.
- **Structured pipeline observability**: Introduce `TurnLogger` with a per-turn UUID so every assistant turn can be traced from user input → Whisper transcript → prompt → LLM output → tool execution → final response. Detailed prompt logging is debug-build only.
- **Permission architecture**: Add missing `CALL_PHONE` and `READ_CONTACTS` permissions to `AndroidManifest.xml`, introduce `PermissionManager` with three-state checking (granted / denied-requestable / permanently-denied), and surface actionable permission events from the tool layer to the UI.
- **First-run setup and permissions screen**: A `SetupScreen` is shown on first launch to explain and request required permissions, with clear handling for permanently denied permissions directing users to App Settings.
- **Light / Dark / System theme**: Replace hardcoded Compose hex colors with a proper Material 3 `LokiTheme` with light and dark color schemes. Theme preference is persisted via DataStore and applied app-wide.

## Capabilities

### New Capabilities
- `conversation-session-scoping`: Two-mode session design — `newChatSession()` for persistent multi-turn chat memory, `newVoiceSession()` for ephemeral per-invocation voice turns. Replaces the single shared `ConversationContext` singleton.
- `permission-aware-tool-execution`: `ToolRegistry` and `GrammarBuilder` accept permission context; unavailable tools are excluded from the grammar and flagged in the system prompt. `PermissionManager` centralises three-state permission checking across the app.
- `assistant-pipeline-observability`: `TurnLogger` provides structured, UUID-correlated logging for every stage of an assistant turn. Debug builds log full prompt content; release builds log only metadata.
- `loki-theme`: Material 3 `LokiColorScheme` with light and dark palettes. `ThemeRepository` persists selection via DataStore. Applied at app root; all UI components use theme tokens instead of hardcoded colours.
- `setup-and-permissions-screen`: First-run onboarding screen and a persistent Settings → Permissions screen listing each tool permission, its status, and an action button (Grant / Open Settings).

### Modified Capabilities
- `conversation-manager`: Session scoping changes the public API — `ConversationManager` now exposes `newChatSession()` and `newVoiceSession()` instead of `processUtterance()` directly; the system prompt now includes tool descriptions and disabled-tool notices.
- `grammar-builder`: `GrammarBuilder.buildFrom()` now accepts a `Context` and filters tools by permission state before constructing the GBNF schema; grammar is cached per tool-set hash.
- `tool-registry`: `ToolRegistry.execute()` returns a richer `ToolPermissionEvent` (requestable vs permanently denied) instead of a generic error string, enabling the UI to show an actionable response.
- `voice-pipeline`: `AssistantSession.startTurn()` calls `conversationManager.newVoiceSession()` to obtain a fresh, ephemeral session rather than sharing the persistent chat context.

## Impact

- **`core:conversation`**: `ConversationManager`, `ConversationContext` — new `ConversationSession` type added; API changes.
- **`core:llm`**: `GrammarBuilder` — permission-filtered tool list parameter added; grammar caching.
- **`core:tools`**: `ToolRegistry` — richer permission event return type; new `PermissionManager`.
- **`core:assistant`**: `AssistantSession` — calls `newVoiceSession()` on each turn start.
- **`core:ui`**: `ChatScreen`, `ChatViewModel` — uses `newChatSession()`; replaces hardcoded colors; wires permission events to UI dialogs; new `SetupScreen` and `PermissionsScreen` composables.
- **`app`**: `AndroidManifest.xml` — adds `CALL_PHONE`, `READ_CONTACTS`; `MainActivity` — new first-run logic; `AppModule` — new `ThemeRepository` and `PermissionManager` bindings.
- **`core:voice/tts`, `core:voice/stt`**: No API changes; `TurnLogger` integration only.
- No external API or library additions required — all changes are within existing dependencies (Hilt, DataStore, Material 3, llama.cpp JNI).
