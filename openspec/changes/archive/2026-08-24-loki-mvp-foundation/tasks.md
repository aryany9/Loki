## 1. Technical Spikes (Validate Before Full Implementation)

- [x] 1.1 **[Spike 1]** Create a minimal Android app that declares `VoiceInteractionService` and `VoiceInteractionSessionService` in the manifest and registers for `ROLE_ASSISTANT`
- [x] 1.2 **[Spike 1]** Verify Loki appears in Android Settings → Default apps → Digital assistant on target Samsung device
- [x] 1.3 **[Spike 1]** Validate system assistant invocation (long-press home or equivalent) launches `VoiceInteractionSession` on unlocked device
- [x] 1.4 **[Spike 1]** Validate lock-screen invocation — session overlay appears over keyguard using `FLAG_SHOW_WHEN_LOCKED` without device unlock
- [x] 1.5 **[Spike 1]** Validate session cancellation/interruption cleans up correctly (no crash, no resource leak)
- [x] 1.6 **[Spike 1]** Document any Samsung-specific behavior encountered; confirm no OEM-specific code is required
- [x] 1.7 **[Spike 2]** Integrate llama.cpp via JNI/CMake on ARM64 Android; confirm build succeeds with a tiny GGUF model
- [x] 1.8 **[Spike 2]** Load a small GGUF model (Gemma 3 1B or Qwen2.5 1.5B / Qwen3.8 4B) and run a basic inference call offline
- [x] 1.9 **[Spike 2]** Implement a minimal `GrammarBuilder` with 3 hardcoded tools; generate GBNF grammar; confirm constrained output over 20 tool-call prompts
- [x] 1.11 **[Spike 3]** Integrate Whisper.cpp (tiny model) via JNI; record microphone audio and produce a transcript in airplane mode
- [x] 1.12 **[Spike 3]** Implement basic VAD silence detection (300–500ms threshold) to gate Whisper batch inference
- [x] 1.13 **[Spike 3]** Wire Android TTS (`TextToSpeech`) to speak a canned response after transcript is produced
- [x] 1.14 **[Spike 3]** Measure end-to-end latency (speech end → TTS start) across 10 test utterances; confirm P50 < 2s on target device
- [x] 1.15 **[Spike 3]** Confirm all three stages (STT, TTS, inference) work fully offline (airplane mode)

## 2. Project Setup and Module Structure

- [x] 2.1 Initialize Kotlin Android project with Gradle version catalog, `minSdk=29`, `compileSdk=latest stable`
- [x] 2.2 Create module structure: `app/`, `core/assistant/`, `core/conversation/`, `core/voice/stt/`, `core/voice/tts/`, `core/llm/`, `core/tools/`, `core/tools/local/`, `core/ui/`
- [x] 2.3 Add Jetpack Compose BOM, Material3, Coroutines, Hilt, and kotlinx-serialization dependencies
- [x] 2.4 Configure CMake build for llama.cpp native library (ARM64 target, Vulkan optional)
- [x] 2.5 Configure CMake build for whisper.cpp native library (ARM64 target)
- [x] 2.6 Set up Hilt dependency injection wiring in `app/` module
- [x] 2.7 Add initial `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` permissions to manifest (others requested on demand)

## 3. Android Assistant Integration (`core/assistant`)

- [x] 3.1 Implement `LokiVoiceInteractionService` extending `VoiceInteractionService`; declare in manifest with correct `<meta-data>` pointing to `xml/interaction_service.xml`
- [x] 3.2 Create `xml/interaction_service.xml` with `sessionService` reference and `supportsAssist` flag
- [x] 3.3 Implement `LokiVoiceInteractionSessionService` extending `VoiceInteractionSessionService`; factory for `LokiVoiceInteractionSession`
- [x] 3.4 Implement `LokiVoiceInteractionSession` extending `VoiceInteractionSession`; delegate all logic to `AssistantSession`
- [x] 3.5 Implement `AssistantSession` — thin coordinator that calls `ConversationManager` and manages session state machine (IDLE, LISTENING, PROCESSING, SPEAKING, ERROR)
- [x] 3.6 Mount `ComposeView` inside session window in `onShow()`; set `FLAG_SHOW_WHEN_LOCKED` and `FLAG_DISMISS_KEYGUARD` for lock-screen support
- [x] 3.7 Handle `onHide()` — cancel all in-progress pipeline work, release microphone and audio resources
- [x] 3.8 Write integration test confirming session lifecycle (start → active → cancel) produces no resource leaks

## 4. Voice Pipeline — STT (`core/voice/stt`)

- [x] 4.1 Define `SttEngine` interface: `startListening()`, `stopListening()`, `cancel()`, events: `onPartialResult(text)`, `onFinalResult(text)`, `onError(error)`
- [x] 4.2 Implement `WhisperSttEngine`: JNI bridge to whisper.cpp, model loading/unloading, audio buffer management
- [x] 4.3 Implement VAD inside `WhisperSttEngine`: silence threshold detection, audio chunk gating, utterance boundary detection
- [x] 4.4 Implement `WhisperSttEngine.cancel()`: stop recording, abort in-progress whisper_full() call, release buffers
- [x] 4.5 Implement `WhisperModelManager`: download, store, and load whisper GGUF/bin model files
- [x] 4.6 Write unit tests for VAD chunking logic and state transitions
- [x] 4.7 Write integration test for `WhisperSttEngine`: record a canned audio clip, confirm transcript matches expected text

## 5. Voice Pipeline — TTS (`core/voice/tts`)

- [x] 5.1 Define `TtsEngine` interface: `speak(text, onDone)`, `stop()`, `isSpeaking(): Boolean`
- [x] 5.2 Implement `AndroidTtsEngine` wrapping `TextToSpeech`; handle initialization, language selection, audio focus, and completion callback
- [x] 5.3 Implement `AndroidTtsEngine.stop()`: cancel current audio synthesis immediately
- [x] 5.4 Write unit tests for `TtsEngine` contract with mock `TextToSpeech`
- [x] 5.5 Write integration test on device: call `speak()`, confirm audio plays without errors

## 6. LLM Engine (`core/llm`)

- [x] 6.1 Define `LlmEngine` interface: `generate(prompt, grammar, onToken, onError)` returning `Flow<String>` or suspending `String`
- [x] 6.2 Implement `LlamaCppLlmEngine`: JNI bridge to llama.cpp, model loading/unloading, sampling configuration
- [x] 6.3 Implement GBNF grammar-constrained sampling in `LlamaCppLlmEngine` via `llama_sampler_init_grammar()`
- [x] 6.4 Implement `LlamaCppLlmEngine.cancel()`: abort active token generation, clean up state
- [x] 6.5 Implement `ModelManager`: handle model file discovery, storage, and loading
- [x] 6.6 Implement dynamic thread count selection based on available CPU cores
- [x] 6.7 Implement `GrammarBuilder`: generate GBNF grammar from `ToolRegistry` tool schemas at runtime
- [x] 6.8 Write unit tests for `GrammarBuilder`: verify valid GBNF output for diverse tool schemas
- [x] 6.9 Run Spike 2 benchmark (20 prompts) as an integration test; confirm 100% grammar compliance

## 7. Grammar Builder (`core/llm`)

- [x] 7.1 Implement `GrammarBuilder.buildFrom(toolRegistry: ToolRegistry): String` — generates GBNF encoding all registered tool names and parameter types
- [x] 7.2 Support parameter types in grammar: `String`, `Int`, `Boolean`, `Enum` (fixed set of values)
- [x] 7.3 Add a `final_response` production rule to the grammar (plain text alternative to tool call)
- [x] 7.4 Implement grammar cache: regenerate only when `ToolRegistry` contents change
- [x] 7.5 Write unit tests: given N registered tools, assert generated GBNF contains exactly those tool names as literals and no others
- [x] 7.6 Write integration test: feed generated GBNF to `LlamaCppLlmEngine`, assert 20+ diverse prompts produce only valid tool-call or response outputs

## 8. Tool Registry and Tool Interface (`core/tools`)

- [x] 8.1 Define `Tool` interface: `name: String`, `description: String`, `parameterSchema: JsonObject`, `execute(arguments: JsonObject): ToolResult`
- [x] 8.2 Define `LocalTool` and `OnlineTool` marker interfaces extending `Tool`
- [x] 8.3 Define `ToolResult` data class: `success: Boolean`, `errorCode: ToolErrorCode?`, `data: JsonObject?`
- [x] 8.4 Define `ToolErrorCode` enum: `PERMISSION_DENIED`, `NOT_FOUND`, `EXECUTION_ERROR`, `VALIDATION_ERROR`, `TIMEOUT`
- [x] 8.5 Implement `ToolRegistry`: register/deregister tools, `getAll()`, `getAvailableTools(offlineOnly: Boolean)`, `execute(name, args)` with permission check and semantic validation
- [x] 8.6 Implement permission check in `ToolRegistry.execute()`: return `ToolResult(success=false, errorCode=PERMISSION_DENIED)` if required permission not granted
- [x] 8.7 Implement argument validation in `ToolRegistry.execute()`: validate against `parameterSchema`, reject empty required strings
- [x] 8.8 Write unit tests for `ToolRegistry`: unknown tool, empty required argument, missing permission, successful execution
- [x] 8.9 Write unit tests for `ToolResult` serialization to/from JSON (for LLM context injection)

## 9. Local Android Tools (`core/tools/local`)

- [x] 9.1 Implement `LookupContactTool`: query `ContactsContract`, return structured matches array; requires `READ_CONTACTS`
- [x] 9.2 Implement `CallContactTool`: initiate call via `Intent.ACTION_CALL` with contact URI; requires `CALL_PHONE`
- [x] 9.3 Implement `DialNumberTool`: initiate call via `Intent.ACTION_CALL` with tel URI; requires `CALL_PHONE`
- [x] 9.4 Implement `OpenAppTool`: resolve package name from app name via `PackageManager`, launch via `Intent`; no special permission required
- [x] 9.5 Implement `GetBatteryStatusTool`: read `BatteryManager` via sticky `ACTION_BATTERY_CHANGED` broadcast; no special permission
- [x] 9.6 Implement `GetCurrentTimeTool`: return current time/date from system clock; no permission required
- [x] 9.7 Implement `SetTimerTool`: create timer via `AlarmManager` or `ClockContract`; requires `SET_ALARM`
- [x] 9.8 Implement `SetAlarmTool`: create alarm via `AlarmManager.RTC_WAKEUP` or `ClockContract`; requires `SET_ALARM`
- [x] 9.9 Implement `MediaControlTool`: send play/pause/next/previous via `MediaController` / `MediaSession`; requires `MEDIA_CONTENT_CONTROL` or notification listener
- [x] 9.10 Implement on-demand permission request flow: each tool triggers `ActivityResultContracts.RequestPermission` on first use, then retries
- [x] 9.11 Write unit tests for each tool using mocked Android APIs (verify correct `ToolResult` structure)
- [x] 9.12 Write integration tests on device for `GetBatteryStatusTool` and `GetCurrentTimeTool` (no permissions required, safe to automate)

## 10. Conversation Manager (`core/conversation`)

- [x] 10.1 Implement `ConversationManager`: entry point `processUtterance(transcript: String, scope: CoroutineScope)`
- [x] 10.2 Implement the bounded agent loop: LLM generate → parse output → if tool call: execute → append result to context → repeat; if final response: emit to TTS; max iterations enforced
- [x] 10.3 Implement `ConversationContext`: turn history list, token budget tracker, `appendUserTurn()`, `appendToolResult()`, `appendAssistantTurn()`, `trim()` (drops oldest tool results)
- [x] 10.4 Implement fast path: if first LLM output is a single unambiguous tool call AND tool returns success AND result is a simple scalar value → use template response, skip second LLM pass
- [x] 10.5 Implement tool-call parser: deserialize LLM output JSON, extract `tool` and `arguments` fields, handle malformed output gracefully
- [x] 10.6 Implement cancellation: expose `cancel()` that cancels the active coroutine `Job`; propagates cancellation to `LlmEngine`, `SttEngine`, `TtsEngine`, `ToolRegistry`
- [x] 10.7 Implement error handling: tool `PERMISSION_DENIED` → request permission; tool `NOT_FOUND` → inform user; max iterations → inform user; LLM error → inform user
- [x] 10.8 Implement session reset: `reset()` clears `ConversationContext` and cancels all pending work
- [x] 10.9 Write unit tests for agent loop: single-tool fast path, two-step clarification loop, max-iterations termination, cancellation mid-loop
- [x] 10.10 Write unit tests for `ConversationContext` token budget tracking and trim behavior

## 11. Voice Interaction UI (`core/ui`)

- [x] 11.1 Create `SessionOverlay` composable: Loki name/branding, session state indicator, partial transcript text, response text area
- [x] 11.2 Define `SessionUiState` sealed class: `Idle`, `Listening(partial: String?)`, `Processing`, `Speaking(response: String)`, `Error(message: String)`
- [x] 11.3 Mount `SessionOverlay` as a `ComposeView` inside `VoiceInteractionSession` window in `onShow()`
- [x] 11.4 Wire `AssistantSession` state machine to `SessionUiState` updates via `StateFlow`
- [x] 11.5 Apply Material3 theming appropriate for an overlay surface (dark background, high contrast)
- [x] 11.6 Verify overlay renders correctly over lock screen on target device
- [x] 11.7 Write UI snapshot tests for each `SessionUiState` variant

## 12. Chat UI (`core/ui` and `app/`)

- [x] 12.1 Create `ChatMessage` model (`id`, `sender: User | Assistant`, `text`, `timestamp`, `toolResult: ToolResult?`)
- [x] 12.2 Create `ChatScreen` composable: message history `LazyColumn`, chat bubbles, text input `TextField`, send button, and inline mic button
- [x] 12.3 Create `ChatViewModel`: manages in-memory message history, triggers `ConversationManager.processUtterance()` with text input (TTS disabled)
- [x] 12.4 Wire inline mic button in `ChatScreen` to record audio via `AudioRecorder` + transcribe via `WhisperBridge`/`SttEngine` and send as message
- [x] 12.5 Set `MainActivity` as the host for `ChatScreen` when launched from the Android launcher
- [x] 12.6 Verify chat flow: text input → LLM inference → tool execution → chat response rendered (no TTS)

## 13. Integration and End-to-End Validation

- [x] 13.1 Wire all modules together via Hilt in `app/`: `SttEngine` → `ConversationManager` → `LlmEngine` + `ToolRegistry` → `TtsEngine`
- [x] 13.2 End-to-end test (manual): invoke Loki from lock screen → say "What's my battery?" → verify correct TTS response without internet
- [x] 13.3 End-to-end test (manual): invoke Loki → say "Call Rahul" with two matching contacts → verify clarification prompt → confirm correct contact called
- [x] 13.4 End-to-end test (manual): invoke Loki → say "Set a timer for 5 minutes" → verify timer created and TTS confirmation
- [x] 13.5 End-to-end test (manual): invoke Loki → trigger interruption mid-TTS → verify clean cancellation and re-listening
- [x] 13.6 End-to-end test (manual): open Loki from launcher → type "What time is it?" → verify message bubble response without TTS
- [x] 13.7 Airplane mode end-to-end test: all 9 local tools function without internet across both voice overlay and chat interface
- [x] 13.8 Install Loki on a second non-Samsung Android device; run Spike 1 checklist items to confirm standard-Android-API compliance
- [x] 13.9 Profile RAM usage during active session with model loaded; confirm usage within acceptable bounds for mid-range device
