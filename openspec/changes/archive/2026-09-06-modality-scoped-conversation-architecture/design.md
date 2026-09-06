## Context

On-device testing with a 2B SLM revealed that running Voice Assistant and Chat Screen on a shared monolithic prompt and turn-control state machine causes critical bugs:
1. Valid Markdown and Java code blocks are stripped and overwritten with plain strings due to parser false-positives and corrective retries.
2. The model appends internal protocol JSON (`{"tool": "ask_user", ...}`) to normal answers because it was instructed that ending a turn without `ask_user` terminates the conversation.
3. Monolithic prompts pin up to 1,000 tokens in the KV cache before turn 1, leaving insufficient headroom on 2,048-token mobile NPU backends and causing severe TTFT latency and OOM compaction crashes.

Voice Assistant requires concise, spoken-first language and turn-yielding signals (`ask_user`) so `AssistantSession` can orchestrate Android audio hardware (Mic, STT, TTS). Chat Screen is an asynchronous visual timeline where users have an omnipresent keyboard, expect rich Markdown, and turn-yielding is the natural default state.

## Goals / Non-Goals

**Goals:**
- Decouple Voice and Chat prompts, memories, tool availability, and UI rendering by interaction modality.
- Establish `ConversationMode` (`VOICE`, `CHAT`) as a first-class master context across the session lifecycle.
- Implement capability-driven tool resolution via decoupled `ToolPolicy` interface: `Effective = Registered ∩ ModelCapabilities ∩ ModePolicy ∩ TaskGates`.
- Clarify and document that `maxTurns = 1` limits each `ConversationSession` to one LLM generation cycle and does NOT limit a Voice Interaction to one spoken exchange.
- Elevate multi-turn task progress into canonical `TaskState` with computed `ExpectedResponseSemantics`, externalizing task state ownership to `AssistantSession`.
- Eliminate parser rejection of Markdown code blocks in `ToolCallParser`.
- Decouple tool execution cards from Markdown text bubbles in Chat UI using fine-grained `ConversationEvent` streaming and `List<ToolInvocation>` correlated by execution IDs.
- Replace static memory limits with dynamic, elastic context budgeting.

**Non-Goals:**
- Rewriting the underlying MediaPipe / LiteRT LLM inference engines.
- Changing STT (Whisper) or TTS audio playback algorithms.
- Redesigning unrelated tools or settings screens.

## Decisions

### Decision 1: First-Class `ConversationMode` Lifecycle Context
- **Decision:** Introduce `enum class ConversationMode { VOICE, CHAT }` in `core:models` and pass it directly to `ConversationSession` at construction.
- **Rationale:** Replaces brittle runtime string checks (`source == "TEXT"`, `source in voiceSources`). `ConversationMode` deterministically drives tool policies, prompt assembly, memory filtering, and token allocations.
- **Alternatives Considered:** Keeping per-turn `source: String` parameter. Rejected due to lack of compile-time safety and duplicated string literals across modules.

### Decision 2: Clarified Voice Interaction vs LLM Session Lifecycle
- **Decision:** Document and enforce: *`maxTurns = 1` limits each `ConversationSession` to one LLM generation cycle; it does not limit a Voice Interaction to one user/assistant exchange. `AssistantSession` may create multiple sessions while resolving a single task.* Multi-round voice interactions are coordinated by `AssistantSession` and bounded by an operational safety circuit-breaker (`private const val MAX_VOICE_ROUNDS = 10`) which triggers an explicit terminal state with spoken user fallback upon tripping.
- **Rationale:** Keeps mobile KV cache compact and prefill latency minimal for every spoken round, while preserving rich multi-round dialogues (e.g. contact disambiguation).
- **Alternatives Considered:** Multi-turn persistent context for Voice. Rejected because accumulating turn history rapidly exhausts the 2,048-token NPU cache and increases spoken latency.

### Decision 3: Decoupled ToolPolicy & Module Dependency Architecture
- **Decision:** Define a pure single-abstract-method (SAM) interface in `core:tools`:
  ```kotlin
  fun interface ToolPolicy {
      fun isAllowed(tool: Tool): Boolean
      companion object {
          val ALL = ToolPolicy { true }
      }
  }
  ```
  `ToolRegistry.getAvailableTools()` accepts `policy: ToolPolicy = ToolPolicy.ALL` (defaulted, ensuring zero breaking changes to existing callers).
  Modality-specific policies (`ChatToolPolicy` excluding `ask_user`, `VoiceToolPolicy`) live in `core:conversation`, preserving the strict dependency hierarchy (`core:conversation` -> `core:tools`, `core:conversation` -> `core:models`, with no dependency from `core:tools` on `core:models`).
- **Rationale:** Keeps `core:tools` pure and decoupled while preventing `ask_user` schemas from ever entering the Chat prompt.
- **Alternatives Considered:** Adding `core:models` dependency to `core:tools`. Rejected to keep low-level tool modules decoupled from conversation-level domain models.

### Decision 4: Separation of Tool Protocol from Turn Protocol
- **Decision:** Distinctly separate **Tool Protocol** (syntax: how tools are invoked via JSON) from **Turn Protocol** (semantics: how turn transitions occur):
  - **Tool Protocol (`PromptSection.TOOL_PROTOCOL`)**: Shared JSON formatting rules (`{"tool": "name", "arguments": {...}}`).
  - **Turn Protocol (`PromptSection.TURN_PROTOCOL`)**:
    - In `Voice`: *"If the task requires more information from the user before you can continue, end your turn by invoking `ask_user` with your question as its text argument. Do NOT end a turn that requires user input with plain text — the user cannot reply to plain text."*
    - In `Chat`: *"If you need information from the user, ask directly in Markdown. Do NOT invoke `ask_user` — it is not available in this context and will cause an error."*
- **Rationale:** Prevents turn-taking semantics from leaking into general tool invocation rules and clarifies that `ask_user` is a turn-yielding signal, not a standard operational tool. Exact wording is critical for 2B model compliance — vague framing causes the model to mix text replies with tool calls or hallucinate `ask_user` in Chat.

### Decision 5: External TaskState Ownership & Guarded Initialization
- **Decision:** `AssistantSession` owns cross-round `TaskState`. `ConversationSession` receives `taskState` as an external constructor/injection parameter and never synthesizes calling state out of thin air. The legacy `init {}` block in `ConversationSession` is strictly guarded (`mode == ConversationMode.VOICE && taskState == null`) and marked for deprecation. `TaskState` exposes a computed property `val expectedSemantics: ExpectedResponseSemantics` (`SELECTION`, `CONFIRMATION`, `MISSING_SLOT`, `FREE_TEXT`) requiring zero constructor churn on `ContactResolution`.
- **Rationale:** Prevents voice-specific calling state from bleeding into Chat sessions and eliminates brittle prompt-fragment synchronization.

### Decision 6: Modular Prompt Assembly with Strict Precedence and Sandboxed User Config
- **Decision:** Assemble prompts via `PromptSection` modules with fixed priority tiers:
  1. Tier 1 (Highest): System Foundation & Safety (Non-overridable)
  2. Tier 2: Modality Profile & Domain Directives (Spoken vs Markdown formatting)
  3. Tier 3: User Configuration (Subordinate preferences delimiter):
     - `PromptSection.USER_CUSTOM_INSTRUCTION` (composed of `systemInstruction` + modality-specific `voiceInstruction`/`chatInstruction`)
     - `PromptSection.SCOPED_MEMORIES`
  4. Tier 1 Recency Anchor: `TOOL_PROTOCOL` & `TURN_PROTOCOL` (pinned at the very end to anchor small model attention)
- **Rationale:** Ensures user instructions and memories can never override system safety directives, domain rules, or formatting protocols on 2B SLMs.

### Decision 7: Abstracted Scoped Memories in `MemoryStore`
- **Decision:** Add `MemoryScope` (`GLOBAL`, `VOICE`, `CHAT`) to `MemoryEntry`. Encapsulate query logic inside `MemoryStore.getMemoriesFor(mode: ConversationMode, maxChars: Int)`.
- **Rationale:** Callers simply request applicable memories without leaking filtering rules or mode checks into prompt builders.

### Decision 8: Dynamic Prompt Budget Allocator
- **Decision:** Allocate memory context dynamically:
  `Remaining Budget = Total KV Capacity - (Output Tokens + System Foundation + Active Tool Schemas + History)`
  Clamped to a modality ceiling (`maxMemoryCapChars`).
- **Rationale:** Avoids blind hardcoded limits (`300` / `800` chars), adapting automatically to varying model KV capacities (2048 vs 4096) and active tool schema sizes.

### Decision 9: Decoupled UI Event Streaming with Execution Correlation IDs
- **Decision:** Internal `ConversationEvent` streams fine-grained tool lifecycle events (`ToolCallStarted(callId)`, `ToolCallCompleted(callId)`, `ToolCallFailed(callId)`) and token deltas. `ToolInvocation.id` represents this concrete execution correlation ID. `ChatViewModel` derives `ChatMessage` containing independent `text: String` (Markdown) and `toolInvocations: List<ToolInvocation>`. Single-tool fields (`toolResult`, `toolName`) are marked `@Deprecated(WARNING)` and all callers migrated.
- **Rationale:** Correlates lifecycle events end-to-end and prevents execution status strings from overwriting streamed Markdown text.

### Decision 10: `ToolCallParser` Markdown Code Block Bugfix
- **Decision:** Remove the legacy check at line 41 of `ToolCallParser.kt` that rejected text containing ` ``` ` before natural language fallbacks. A response containing code fences without `"tool":` markers directly returns `ParsedLlmResponse.DirectResponse(trimmed)`.
- **Rationale:** Eliminates the root cause of code block stripping and unnecessary corrective retries.

## Risks / Trade-offs

- **[Risk] Existing serialized memories lack `scope` field** → Mitigation: `MemoryEntry.scope` defaults to `MemoryScope.GLOBAL` with `@Serializable` and `ignoreUnknownKeys = true`, ensuring 100% backward compatibility with existing JSON files.
- **[Risk] Chat UI rendering overhead with multiple tool cards** → Mitigation: Use Compose `derivedStateOf` and key each `ToolResultCard` by `ToolInvocation.id`.
- **[Risk] Dynamic memory budget calculation errors on edge cases** → Mitigation: Clamp memory budget to `[0, maxMemoryCapChars]` so memory never receives negative or unbounded allocations.
- **[Risk] Silent semantic changes with deprecated ChatMessage getters** → Mitigation: Explicitly migrate all call sites in `core:ui` and tests to `toolInvocations` as part of this change.
