## Context

`ConversationSession.buildSystemPrompt` (ConversationSession.kt:339–424) builds one monolithic prompt per session: persona, user `AgentConfig.systemInstruction`, language directive, up to 10 memories, ALL permission-granted tool schemas, one ~900-char guidance paragraph mixing memory/history guidance with the entire contact-calling protocol, disabled-tool notices, and the JSON protocol line. LiteRT-LM prefills this once into the KV cache per `startConversation` (LlmEngine.kt:33–53); subsequent `generate` calls carry only the new message — so nothing about the prompt can change mid-conversation today, which is why every capability's instructions ride along in every generation.

Tools are flat: `Tool` (name, description, parameters, permissions, `requiresConfirmation`) in a flat `ToolRegistry`, no grouping. Tool results return to the model as stringified `ToolResult.data` in the next ReAct prompt. `ModelCapabilities` describes model modality (text/audio/vision/toolCalling) and is used only by voice-strategy resolution — orthogonal to task domain.

The contact-calling flow demonstrates the failure mode: on a 6-candidate lookup the model produced prose instead of JSON (costing a corrective regeneration), spoke phone numbers despite a "NAME only" instruction, and arbitrarily narrowed to one candidate. All three are prompt-adherence failures on the hardest generation turn; prose guardrails cannot fix them for a 4B model.

## Goals / Non-Goals

**Goals:**
- Capability-scoped prompts and tool schemas so each generation carries only relevant instructions.
- Application-owned task state with machine-validated tool calls ("LLM interprets → application validates → tool executes").
- Natural-language understanding and response generation remain fully LLM-owned — no hardcoded utterances, no confirmation keywords, no keyword-based intent routing.
- Keep LiteRT-LM's KV-prefill economics; compose dynamically via per-turn messages, not conversation resets.
- Smallest architecture that extends to music/web/messaging without new frameworks.

**Non-Goals:**
- No intent classifier, capability router, or generic plugin/DSL framework.
- No chat-path behavior change (`ChatViewModel` flow, confirmation buttons).
- No change to voice-input strategies, `ModelCapabilities`, or STT/TTS pipelines.
- No grammar-constrained-decoding rollout beyond what `GrammarBuilder` already does (scoped to the visible set).

## Decisions

### D1 — Capability identity lives on `Tool`; selection is emergent from the ReAct loop

`Tool` gains `val capability: String` with default `"general"`. There is no central capability→tools mapping table; `ToolRegistry` groups by the field tools already declare. A capability activates when the model calls a tool of that capability and no capability is active (or the active one's state is clean); it deactivates when the task state completes, the model produces a final response with no pending state, or the user cancels. The first turn of any session has no active capability and sees all permission-granted tools — that turn is precisely where capability is unknown, so full visibility is correct, not a gap.

Rationale: the ReAct loop already performs capability selection implicitly via tool calls. Adding a router or classifier would duplicate it, add latency, and reintroduce keyword fragility.

### D2 — "General" semantics: context-free tools only, with an explicit governance rule

`general` must not become a dumping ground. A tool MAY be declared `general` only if it satisfies all three criteria:

1. **State-free:** it neither reads from nor writes to any `TaskState`.
2. **Context-free:** invoking it is meaningful during any other capability's active task (e.g. "what time is it?" mid-contact-selection).
3. **Non-committal:** executing it never commits the user to a multi-turn task.

Under this rule: `remember_fact`, `search_chat_history` are general (memory/history is conversation-level); ambient one-shot queries (`get_current_time`, `get_battery_status`) are general (criterion 2 — plausible during any task; criterion 1 and 3 hold). Domain tools (`lookup_contact`, `call_contact`, `dial_number`, `set_timer`, `media_control`, app/device toggles, …) are capability-scoped even if they are one-shot, because they are not meaningful *as part of* an unrelated active task and would compete with its instructions. Enforcement: the general list is asserted in unit tests (a test enumerates `capability == "general"` tools and the list is reviewed on change), and design review of any new tool checks the three criteria. Default for a new tool is its domain capability, never `general`.

### D3 — TaskState owns its advancing tool — no separate mapping

The state-advancing-tool invariant is derived from the state itself, not from a mapping registry. `TaskState` is a sealed interface in `core/conversation`; each variant exposes:

- `advancingTool: String?` — the tool that can resolve or complete the state right now, computed from the state's own fields. For `ContactResolution`: candidates unresolved → `"select_contact"`; selected but unconfirmed → `"call_contact"`; task-ready → `null`.
- `resolved: Boolean` — whether the state still blocks capability switches.

Because `TaskState` variants are constructed and mutated only by `ConversationSession`, the advancing tool is always consistent with the state by construction. The visibility scoping rule then consumes it: while a state is pending, `advancingTool` is guaranteed callable. This makes the invariant a property of data flow (state → scoping), testable with a single unit test per state variant, and impossible to drift from a lookup table. No new mapping system exists.

Advancing tools themselves are internal-reserved (`select_contact` handled by the session, not a user-facing registry tool, or a minimal internal tool if the registry abstraction demands it — decided at implementation, both fit the D1/D5 mechanics).

### D4 — Prompt composition: KV-prefilled core + per-turn dynamic composition

| Layer | Lifetime | Mechanism |
|---|---|---|
| Core prompt (persona, JSON protocol, language directive, memories) | Session | KV-prefilled via `startConversation` (unchanged) |
| Tool schemas | Per turn — full set at session start, `general` + active-capability set while scoped | Per-turn message composition |
| Capability instructions | Injected in full on activation; 1-line reminder while active | Per-turn message composition |
| Task state | Per turn — rendered fresh from `TaskState`, never accumulated | Per-turn message composition |
| Conversation history | Existing `ConversationContext` trimming | Unchanged |

The tool-schema listing MUST move out of the static system prompt: it is KV-prefilled today, so leaving it there would silently keep all tools visible on every turn and undo scoping. LiteRT-LM accepts fresh text on each `generate`, so schemas ride on the per-turn composition at negligible cost (re-sent only when the visible set changes). Capability instructions use the same channel — injected on the activation turn (the tool-result continuation), with a compact reminder on subsequent turns. No `startConversation` re-init is needed mid-task, preserving KV-cache economics and dialogue history.

### D5 — Capability-scoped tool visibility; omit, don't expose-and-reject

`ToolRegistry.getAvailableTools(context, permissionManager, activeCapability)` applies three filters in order: permission state (existing), environment availability (existing `LocalTool`/`OnlineTool` offline split), capability scope (new). Unavailable tools are OMITTED from the callable set. Rationale from measured behavior: exposing an invalid tool costs a wasted generation plus a corrective round (2–7s on-device, observed), and prose cannot reliably stop a small model from calling a schema it can see — absence is the only dependable guardrail and the token saving is the point of the architecture. Tools omitted for permission/environment reasons still appear in the existing disabled-tools notice when the user might plausibly ask for them, so the model can explain. Out-of-scope tool calls the model emits anyway are handled by the existing coached-deferral mechanism (friendly correction, no execution).

`GrammarBuilder.buildFrom` receives the same scoped set, so grammar-constrained decoding enforces the scope syntactically — an out-of-scope tool name becomes unproducible, not merely discouraged. Grammar regenerates when the scope changes (capability activation/deactivation), exactly as it already does on registry membership changes.

`ModelCapabilities` remains orthogonal (model modality → voice strategy); the only intersection is `supportsToolCalling = false` → no tools, already implicit. `AssistantSession` does not participate in tool scoping; its STT demotion path is unaffected.

### D6 — Contact resolution end-to-end (spec-through-line)

"Call Mom" → `lookup_contact` (activates `calling`) → app executes lookup, stores `ContactResolution(candidates=[ids+names], no numbers in model context)` → per-turn state block tells the model the candidate names/IDs and that numbers are unavailable to it → model naturally asks which one (any phrasing) → "the second one" → model emits `select_contact(candidate_id)` → app validates ID against live state → state advances, model asks confirmation naturally → "yes, go ahead" → model emits the advancing call → app validates selection + confirmation, resolves the ID to the real number (app-owned data, never model-provided), executes `call_contact` → spoken response rendered by the app from the NAME. No utterance is hardcoded; every sentence is model-generated; every identity/number/confirmation is app-validated.

## Risks / Trade-offs

- [Per-turn schema/token cost] → Scoped sets are smaller than today's full list; re-sent only on set changes; net token usage decreases for capability-active turns.
- [Model attempts out-of-scope tools] → Coached deferral handles it gracefully; grammar scoping makes it unproducible when grammar is active.
- [State-machine bugs block the user] → `advancingTool` invariant is unit-tested per state variant; `resolved`/timeout paths guarantee the session can always fall back to a final response.
- [Chat regression] → Chat starts capability-free and sees the full permission-granted set; existing confirmation-gate tests retained as regression guards.
- [Over-fragmentation as capabilities grow] → Adding a capability = one string on its tools + one prompt section + (optionally) one state variant; nothing central changes.

## Migration Plan

1. Land D1/D2/D5 mechanics (capability field, scoped registry, grammar scoping) with full visibility default — behavior-neutral.
2. Land D3/D4 (TaskState + per-turn composition) with the calling capability as the first consumer; remove the prose contact-guidance paragraph in the same step.
3. Rollback = revert; no persisted-format changes.

## Open Questions

- Whether `select_contact` is modeled as an internal reserved tool in the registry or a reserved JSON shape parsed beside `ToolCallParser` — decided at implementation; both satisfy D3/D5 mechanics.
