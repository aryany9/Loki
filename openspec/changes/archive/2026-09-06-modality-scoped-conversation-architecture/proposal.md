## Why

On-device testing with a 2B SLM revealed that forcing the Voice Assistant and the Chat Screen to share a monolithic system prompt and turn-control state machine causes severe user-facing defects: rich Markdown and code blocks are stripped into plain text, the model inappropriately appends voice protocol JSON (`ask_user`) to chat responses, and monolithic prompts consume up to 50% of mobile NPU/GPU KV cache before turn 1. 

Voice and Chat are fundamentally distinct interaction modalities: Voice requires concise spoken phrasing and conversational turn-yielding (`ask_user`) to coordinate Android audio hardware via `AssistantSession`, whereas Chat is an asynchronous visual timeline with an omnipresent keyboard where turn-yielding is default and `ask_user` is completely counterproductive. Decoupling prompts, memory retrieval, tool availability, and UI streaming by interaction modality resolves these issues and eliminates device OOM risks.

## What Changes

- **Explicit `ConversationMode` (`VOICE`, `CHAT`)**: Introduce a first-class enum driving session creation, prompt assembly, memory retrieval, tool policies, and output budgets, replacing ad-hoc string comparisons (`source == "TEXT"`).
- **Decoupled Prompt Assembly with Strict Precedence**: Refactor monolithic prompt generation into modular `PromptSection`s with fixed privilege tiers: System Foundation/Safety (Highest) → Modality Profile → Domain Directives → User Custom Instructions → Scoped Memories → Recency Anchor Protocol (`TOOL_PROTOCOL`).
- **Capability-Driven Tool Policy**: Replace hardcoded `if (source == TEXT) exclude ask_user` with an intersection pipeline: `Effective Tools = Registered ∩ ModelCapabilities ∩ ModePolicy ∩ TaskGates`. Chat mode policy excludes `ask_user` so its schema is never injected and cannot be hallucinated.
- **Clarified Voice Session Lifecycle**: Explicitly document and enforce that `maxTurns = 1` limits each `ConversationSession` to one LLM generation cycle to keep KV cache compact; it does NOT limit a Voice Interaction to one user/assistant exchange. `AssistantSession` orchestrates multi-round voice dialogues (guarded by `MAX_VOICE_ROUNDS = 10`) across fresh, single-turn LLM sessions.
- **Canonical Multi-Turn `TaskState`**: Elevate in-flight task context (such as contact disambiguation) into typed `TaskState` with `ExpectedResponseSemantics`, eliminating brittle prompt-fragment synchronization and redundant re-lookups.
- **Scoped User Memories & Abstracted Retrieval**: Add `MemoryScope` (`GLOBAL`, `VOICE`, `CHAT`) to `MemoryEntry` and encapsulate query logic in `MemoryStore.getMemoriesFor(mode, budget)`.
- **Dynamic Context Budget Allocator**: Replace hardcoded character limits with elastic memory budgeting calculated from available KV capacity after output reservation, fixed prompt, active tool schemas, and history.
- **Decoupled UI Event Streaming**: Upgrade internal `ConversationEvent` to emit fine-grained tool lifecycle events (`ToolCallStarted`, `ToolCallCompleted`, `ToolCallFailed`) and token deltas, while `ChatViewModel` derives `ChatMessage` with `toolInvocations: List<ToolInvocation>` and Markdown text rendered in independent UI sections.
- **`ToolCallParser` Markdown Code Block Bugfix**: Fix line 41 in `ToolCallParser.kt` so valid Markdown containing code blocks without `"tool":` markers is recognized directly as `DirectResponse(raw)` rather than flagged as `Malformed`.

## Capabilities

### New Capabilities
- `modality-prompt-assembly`: Modular prompt section pipeline with fixed precedence ordering, modality-specific profiles, and recency-anchored protocol instructions.
- `capability-driven-tool-policy`: Multi-gate tool resolution pipeline resolving effective tools via intersection of registry, model capabilities, modality policy, and dynamic task gates.

### Modified Capabilities
- `conversation-session-scoping`: Explicitly bind `ConversationSession` to `ConversationMode`, document and enforce the distinction between voice interactions and single-generation LLM sessions (`maxTurns = 1`), and configure modality-scoped output budgets.
- `persistent-memory`: Add `MemoryScope` (`GLOBAL`, `VOICE`, `CHAT`), encapsulate modality filtering and elastic budgeting inside `MemoryStore.getMemoriesFor(mode, budget)`.
- `chat-message-rendering`: Support multiple sequential tool executions on a single message via `toolInvocations: List<ToolInvocation>` rendered as collapsible cards beneath independent Markdown content.

## Impact

- **`core:conversation`**: `ConversationSession`, `ConversationManager`, `ToolCallParser`, `MemoryStore`, and `TaskState`.
- **`core:assistant`**: `AssistantSession` interaction loop, multi-round voice turn coordination with canonical `TaskState`.
- **`core:models`**: `AgentConfig` supports `voiceInstruction` and `chatInstruction`; `ConversationMode` enum introduced.
- **`core:ui`**: `ChatMessage`, `ChatViewModel`, `ChatScreen` collapsible tool cards, and Settings/Memory screens for scoped instructions and memories.
