## Why

On-device 2B models (e.g. Gemma 2B on NPU) cannot reliably maintain a multi-turn state machine when governed purely through prose instructions in system prompts. During contact disambiguation (e.g. "Call Mom" with multiple matches), the LLM currently sees conflicting negative prompt rules alongside both `ask_user` and `call_contact` in the tool grammar, causing the model to repeatedly re-ask the same question ("Which Mom?") when the user responds with "Just Mom" or "Only Mom".

By shifting legal action governance into Kotlin state machines and grammar-level tool scoping (Option B) paired with conservative unique exact-name pre-selection (Option C), the LLM only needs to interpret the user's utterance while Kotlin guarantees that only state-valid transitions can execute.

## What Changes

- **Deterministic State-Scoped Tool Grammar (Foundation B)**:
  - During `CONTACT_DISAMBIGUATION` (`selectedId == null`), the grammar strictly allows only `select_contact(candidate_id)` (and conversational cancellation); `ask_user`, `call_contact`, and `lookup_contact` are removed from the active grammar.
  - During `CALL_CONFIRMATION` (`selectedId != null`, unconfirmed), the grammar strictly allows confirmation response tools / verbal confirmation handling, and forbids `call_contact` directly until affirmed.
  - Only when the state reaches `CONFIRMED` is `call_contact(candidate_id)` exposed and executed.
- **Unique Exact-Match Pre-selection (Optimization C)**:
  - When `lookup_contact` returns multiple contacts, if exactly one contact's display name matches the query (`name.equals(query, ignoreCase = true)`) and no duplicate exact matches exist, that contact is eligible for automatic selection, skipping `CONTACT_DISAMBIGUATION` and proceeding directly to confirmation.
- **System Prompt as Context, Not Governance**:
  - Remove complex negative constraints and state enforcement rules from the system prompt. Prompts provide semantic context for what the current state means, while Kotlin enforces valid transitions.

## Capabilities

### Modified Capabilities
- `conversation-manager`: Scopes active tools and constrained decoding grammar strictly to legal state transitions (`CONTACT_DISAMBIGUATION`, `CALL_CONFIRMATION`), and integrates unique exact display-name matching.
- `action-confirmation`: Enforces the explicit confirmation state transition (`CALL_CONFIRMATION` -> `CONFIRMED` -> `call_contact`) rather than exposing destructive action tools directly during confirmation turns.
- `tool-registry`: Supports state-aware tool gating based on active `TaskState` variants.

## Impact

- `core/conversation`: `ConversationSession`, `GrammarBuilder`, `ToolCallParser`, `TaskState`.
- `core/tools`: `ToolRegistry`, `CallContactTool`, `LocalTools`.
- `core/assistant`: `AssistantSession` follow-up turn routing.
