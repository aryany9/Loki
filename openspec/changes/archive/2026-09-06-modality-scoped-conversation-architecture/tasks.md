## 1. Domain Models, Enums & Memory Store

- [x] 1.1 Add `enum class ConversationMode { VOICE, CHAT }` to `core:models`
- [x] 1.2 Add `enum class MemoryScope { GLOBAL, VOICE, CHAT }` with `isApplicableTo(mode: ConversationMode)` in `core:conversation`
- [x] 1.3 Add `scope: MemoryScope = MemoryScope.GLOBAL` to `MemoryEntry` in `MemoryStore.kt` ensuring backward compatibility
- [x] 1.4 Implement `MemoryStore.getMemoriesFor(mode: ConversationMode, maxChars: Int, maxCount: Int)` with budget enforcement
- [x] 1.5 Add `voiceInstruction: String = ""` and `chatInstruction: String = ""` to `AgentConfig` in `ModelTypes.kt`
- [x] 1.6 Add unit tests in `MemoryStoreTest.kt` for scoped retrieval and character budgeting

## 2. ToolCallParser Bugfix & Natural Language Fences

- [x] 2.1 Refactor `ToolCallParser.kt` to classify responses containing Markdown code blocks without `"tool":` markers as `ParsedLlmResponse.DirectResponse`
- [x] 2.2 Add unit tests in `ToolCallParserTest.kt` verifying Java/Kotlin code blocks containing `{ ... }` braces, capability lists, and markdown with extra text parse cleanly without triggering `Malformed` or truncation

## 3. Capability-Driven Tool Policy

- [x] 3.1 Define decoupled `fun interface ToolPolicy` with default `ToolPolicy.ALL` in `core:tools` without adding dependencies on `core:models`
- [x] 3.2 Update `ToolRegistry.getAvailableTools` to accept `policy: ToolPolicy = ToolPolicy.ALL` (default parameter preserving caller compatibility)
- [x] 3.3 Implement `ChatToolPolicy` (excluding `ask_user`) and `VoiceToolPolicy` in `core:conversation`
- [x] 3.4 Add unit tests verifying `ask_user` is excluded from effective tools when `mode == ConversationMode.CHAT` and present when `mode == ConversationMode.VOICE`

## 4. Modular Prompt Assembly & Dynamic Budgeting

- [x] 4.1 Define `PromptSection` enum with explicit precedence priorities and separation of `TOOL_PROTOCOL` (syntax) from `TURN_PROTOCOL` (semantics)
- [x] 4.2 Refactor `ConversationSession.buildCoreSystemPrompt()` into modular assembly (`buildCommonSections`, `applyVoiceProfile`, `applyChatProfile`), wiring `systemInstruction` + modality instructions in Tier 3 subordinate preferences
- [x] 4.3 Implement dynamic prompt budget allocator calculating remaining KV capacity after reserving output tokens, foundation, tool schemas, and history
- [x] 4.4 Add unit tests verifying: (a) `VOICE` prompt contains `ask_user` invocation instruction and spoken-first phrasing; (b) `CHAT` prompt does NOT contain any `ask_user` instruction text and does contain Markdown guidance; (c) memory budgeting respects dynamic cap; (d) `TOOL_PROTOCOL` and `TURN_PROTOCOL` sections appear after `USER_CUSTOM_INSTRUCTION` and `SCOPED_MEMORIES` (recency anchor placement)

## 5. ConversationSession & AssistantSession Coordination

- [x] 5.1 Update `ConversationSession` constructor to accept `mode: ConversationMode`, enforce modality output tokens (128 for Voice, 512 for Chat), and document: `maxTurns = 1 limits each ConversationSession to one LLM generation cycle; it does not limit a Voice Interaction to one user/assistant exchange. AssistantSession may create multiple sessions while resolving a single task.`
- [x] 5.2 Update `ConversationManager.newChatSession()` and `newVoiceSession()` to pass explicit `ConversationMode`
- [x] 5.3 Add computed `expectedSemantics: ExpectedResponseSemantics` on `ContactResolution` / `TaskState`, and guard legacy `init {}` state synthesis with `if (mode == ConversationMode.VOICE && taskState == null)`
- [x] 5.4 Refactor magic number 10 in `AssistantSession.kt` into `private const val MAX_VOICE_ROUNDS = 10`, maintain `TaskState` across rounds, and add explicit terminal state and spoken fallback when tripped
- [x] 5.5 Add unit tests in `ConversationSessionTest` and `AssistantSessionTest`

## 6. Event-Driven Tool Invocation & Chat UI Upgrades

- [x] 6.1 Update `ConversationEvent` with fine-grained tool lifecycle events (`ToolCallStarted(callId)`, `ToolCallCompleted(callId)`, `ToolCallFailed(callId)`)
- [x] 6.2 Define `data class ToolInvocation(val id: String = UUID.randomUUID().toString(), val toolName: String, val arguments: Map<String, Any?>, val result: ToolResult?, val isExecuting: Boolean)` as a concrete execution correlation ID
- [x] 6.3 Update `ChatMessage` to add `toolInvocations: List<ToolInvocation>`, annotate single-tool accessors (`toolResult`, `toolName`) with `@Deprecated`, and migrate all call sites across `core:ui` and tests
- [x] 6.4 Refactor `ChatViewModel` event reducer to accumulate `toolInvocations` using `callId` correlation without overwriting message `text`
- [x] 6.5 Update `ChatScreen.kt` to render `Markdown(message.text)` independently with a list of collapsible `ToolResultCard` dropdowns keyed by `invocation.id`
- [x] 6.6 Update `ChatViewModelTest` to verify multi-tool turn accumulation and clean text streaming
