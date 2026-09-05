## Why

Loki runs small on-device LLMs (e.g. `gemma-4-E4B-it`), but the system prompt is a single monolith built in `ConversationSession.buildSystemPrompt`: persona, language, memories, ALL tool schemas, and a ~900-char paragraph of mixed tool guidance covering every capability at once, prefilled once into the LiteRT-LM KV cache. On the contact-calling flow this produced verified failures: plain-text output instead of JSON (wasting a full corrective generation), the model reading phone numbers aloud despite instructions, and the model arbitrarily picking one candidate instead of asking. Root cause: every capability's instructions compete in every generation, and the model is treated as the source of truth for facts (identities, numbers) it should only reference.

The architectural principle: **LLM interprets → Application validates → Tool executes.** The LLM keeps natural-language understanding and response generation; the application owns facts, state, validation, permissions, safety, and tool execution.

## What Changes

- **Capability grouping on tools.** `Tool` gains `capability: String` (default `"general"`). A tool is `general` only if it genuinely makes sense regardless of active capability (governance rule defined in design D2); domain tools declare their capability (`calling`, `device`, …).
- **Capability-scoped tool visibility.** `ToolRegistry.getAvailableTools` gains an `activeCapability` parameter: while a capability is active the LLM sees only `general` + active-capability tools; unavailable tools (missing permission, offline) are omitted from the callable set entirely, not exposed-and-rejected. Out-of-scope tool calls from the model are handled by the existing coached-deferral mechanism.
- **Per-turn prompt composition.** Core prompt (persona, language, JSON protocol, memories) stays KV-prefilled; tool schemas, capability instructions, and task state move into the per-turn message composition. Capability instructions are injected on activation and carried as a compact reminder while the task is active; nothing dynamic is baked into the static system prompt.
- **Task-state contract.** `ConversationSession` owns a typed `TaskState` (starting with `ContactResolution`: candidate IDs + names only — phone numbers never enter model context). Each state variant derives its own *advancing tool* (the tool that resolves/completes it), which the visibility scoping guarantees remains callable while the state is pending. The application validates every tool call against the state before execution; the model references app-issued IDs only.
- **Capability selection by the existing ReAct loop.** No keyword router, no intent classifier: the model's tool calls select the capability; activation/deactivation rules live in `ConversationSession`.
- **Grammar stays in sync.** `GrammarBuilder` generates from the scoped visible tool set and regenerates when the scope changes.
- **Deterministic contact resolution & call announcement.** `LookupContactTool`/`CallContactTool` work with candidate IDs; the spoken "Calling …" response is rendered by the application from the contact's name, never the raw number.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `tool-registry`: `Tool` contract gains `capability`; `ToolRegistry` becomes the authority for capability-scoped tool visibility; unavailable tools are omitted rather than exposed.
- `conversation-manager`: system-prompt composition changes from a monolithic KV-prefilled prompt to core-prompt + per-turn composition (scoped tool schemas, capability instructions, task state); capability activation/deactivation lifecycle added; task-state contract added.
- `grammar-builder`: grammar is generated from the capability-scoped visible tool set and regenerated when the scope changes.

## Impact

- `core/tools/Tool.kt` — `capability` member; all ~14 local tools annotated.
- `core/tools/ToolRegistry.kt` — scoped `getAvailableTools`/`getDisabledTools`; out-of-scope call rejection surfacing.
- `core/conversation/ConversationSession.kt` — prompt composer refactor, capability lifecycle, `TaskState` contract, app-side validation, name-based fast-path rendering.
- `core/conversation/ConversationManager.kt` — unchanged surface; `TaskState` lives per-session.
- `core/conversation/GrammarBuilder` (wherever located under core) — scoped generation.
- `core/tools/local/LookupContactTool.kt`, `CallContactTool.kt` — candidate-ID results; `call_contact` accepts `candidate_id`.
- Chat path (`ChatViewModel`) unchanged — starts with no active capability, sees all permission-granted tools.
- Supersedes the prose-only contact guidance paragraph in `buildSystemPrompt` (the machine-enforced state contract replaces it).
