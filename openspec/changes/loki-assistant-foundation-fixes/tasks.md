## 1. Observability Foundation

- [x] 1.1 Create `TurnLogger.kt` in `core:conversation` generating UUID correlation IDs for each assistant turn and logging pipeline events under tag `LokiTurn`
- [x] 1.2 Gate full prompt string logging in `TurnLogger` behind `BuildConfig.DEBUG`
- [x] 1.3 Add transcript logging to `WhisperSttEngine` and correlation ID tracking to `AssistantSession`

## 2. Conversation Session Scoping & System Prompt Fix

- [x] 2.1 Implement `ConversationSession` in `core:conversation` to represent a single assistant interaction context
- [x] 2.2 Update `ConversationManager` to expose `newChatSession()` (persistent context for text chat) and `newVoiceSession()` (ephemeral single-turn context for voice)
- [x] 2.3 Update `AssistantSession.startTurn()` to call `conversationManager.newVoiceSession()` on every invocation
- [x] 2.4 Update `ChatViewModel` to create and retain a `newChatSession()`
- [x] 2.5 Rebuild system prompt construction in `ConversationSession` to list available tools with one-line descriptions and explicitly state disabled tools with missing permissions

## 3. Permission Architecture & Grammar Filtering

- [x] 3.1 Declare `android.permission.CALL_PHONE` and `android.permission.READ_CONTACTS` in `AndroidManifest.xml`
- [x] 3.2 Create `PermissionManager` in `core:tools` supporting `GRANTED`, `REQUESTABLE`, and `PERMANENTLY_DENIED` states
- [x] 3.3 Update `GrammarBuilder.buildFrom()` to accept `Context`/`PermissionManager` and filter out non-granted tools from the GBNF grammar schema
- [x] 3.4 Implement GBNF grammar caching in `GrammarBuilder` keyed on sorted tool-name set
- [x] 3.5 Refactor `ToolRegistry.execute()` to return sealed `ToolExecutionResult` including `PermissionRequired(permission, state)`

## 4. Lifecycle & Cancellation Guard

- [x] 4.1 Add cancellation state guard (`@Volatile cancelled: Boolean`) to `LlamaCppLlmEngine` to prevent redundant native cancellations
- [x] 4.2 Audit `LokiVoiceInteractionSession.onHide()` and `onDestroy()` to prevent double-cancel invocations

## 5. Loki Theme & DataStore Persistence

- [x] 5.1 Create `LokiTheme.kt` with Material 3 `LokiDarkColorScheme` and `LokiLightColorScheme`
- [x] 5.2 Create `ThemeRepository` backed by `DataStore<Preferences>` persisting `DARK`, `LIGHT`, and `SYSTEM` modes
- [x] 5.3 Update `themes.xml` parent style to `Theme.Material3`
- [x] 5.4 Update `MainActivity` to collect `ThemeMode` and wrap root UI in `LokiTheme`
- [x] 5.5 Refactor `ChatScreen.kt` and `VoiceSessionOverlay` to replace hardcoded hex colors with `MaterialTheme.colorScheme` tokens

## 6. First-Run Setup & Permissions Screen

- [x] 6.1 Implement `SetupScreen` for first-run onboarding to request `RECORD_AUDIO` and explain optional tool permissions
- [x] 6.2 Implement `PermissionsScreen` listing all tool permissions with status and action buttons ("Grant" / "Open Settings")
- [x] 6.3 Wire `first_run_complete` flag in DataStore to toggle between `SetupScreen` and `ChatScreen` on app launch
- [x] 6.4 Add permissions navigation button to `ChatScreen` top app bar

## 7. Verification & Build Validation

- [x] 7.1 Verify unit tests pass in `core:conversation`, `core:tools`, `core:llm`, and `core:ui`
- [x] 7.2 Run `./gradlew assembleDebug` to ensure a clean build
