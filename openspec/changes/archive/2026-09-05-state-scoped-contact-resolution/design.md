## Context

Loki runs quantized LLMs (e.g., Gemma 2B) locally on Snapdragon NPU devices. In the previous implementation, conversational state transitions across multi-turn voice dialogs were managed primarily through natural language prompt instructions. During contact resolution, the model received both `ask_user` and `call_contact` in its tool grammar alongside conflicting negative prompt rules (e.g. "confirmation already asked", "invoke call_contact only if affirmed", "do not re-ask").

When responding to disambiguation turns (e.g. "Just Mom" or "The first one"), the 2B model struggled with multi-constraint in-context reasoning, falling back to reproducing the `ask_user` tool call and echoing the disambiguation question indefinitely.

## Goals / Non-Goals

**Goals:**
- Shift multi-turn state transition legality from prompt prose into Kotlin state machines and BNF grammar scoping.
- Restrict active tool grammar in each state to only legal transition actions:
  - `CONTACT_DISAMBIGUATION`: only `select_contact(candidate_id)` is exposed. `ask_user`, `call_contact`, and `lookup_contact` are excluded.
  - `CALL_CONFIRMATION`: only confirmation responses are exposed. `call_contact` is forbidden until affirmed.
  - `CONFIRMED`: `call_contact(candidate_id)` is executed.
- Add unique exact display-name pre-selection: when `lookup_contact` returns multiple contacts, if exactly one contact's display name uniquely matches the query string (`name.equals(query, ignoreCase = true)`), it is eligible for automatic selection, skipping disambiguation.
- Re-position the system prompt as contextual description rather than governance logic.

**Non-Goals:**
- Replace the LLM's natural language understanding with hardcoded regex keyword matchers (the LLM still interprets the user's utterance to choose the `candidate_id` or determine affirmation).
- Remove the verbal confirmation requirement before placing a call.

## Decisions

### Decision 1: Explicit 4-Stage State Machine for Calling Capability

```
[IDLE / GENERAL]
       │  "Call Mom" -> lookup_contact("Mom")
       ▼
[Optimization C: Unique exact name check]
  ├── YES: Unambiguous match -> candidate selected (isAsked = false)
  └── NO: Multiple candidates
             │
             ▼
[CONTACT_DISAMBIGUATION] (taskState: ContactResolution, selectedId == null)
       │  Grammar: [select_contact(candidate_id)]  (ask_user & call_contact excluded)
       │  User: "Just Mom" / "The first one"
       │  LLM emits: select_contact("c3")
       │  Kotlin validates candidate_id membership
       ▼
[CALL_CONFIRMATION (Unasked)] (taskState: ContactResolution, selectedId == "c3", isAsked = false)
       │  Grammar: [ask_user(text)]  (call_contact excluded until question is asked)
       │  App / LLM asks: "Shall I call Mom, the number ending in 21?"
       │  Transition: isAsked -> true
       ▼
[AWAITING_CONFIRMATION] (taskState: ContactResolution, selectedId == "c3", isAsked = true, unconfirmed)
       │  Grammar: [call_contact(candidate_id)]  (ask_user excluded to prevent loop)
       │  User: "Yes" / "Haan karo" / "No, cancel"
       ├── Affirmation: LLM emits call_contact("c3") -> transitions to CONFIRMED
       └── Denial: LLM emits conversational cancellation -> state cleared
       ▼
[CONFIRMED]
       │  call_contact executes with app-resolved phone number
       ▼
[COMPLETED]
```

### Decision 2: State-Scoped Grammar Action Gating
`ToolRegistry.getAvailableTools` and `GrammarBuilder` accept the active `TaskState` (implementing `TaskStateGate`) and filter tools strictly:
- In `CONTACT_DISAMBIGUATION`: `select_contact` is exposed; `ask_user`, `call_contact`, and `lookup_contact` are excluded.
- In `CALL_CONFIRMATION` (`!isAsked`): `ask_user` is exposed to generate the question; `call_contact` is hidden.
- In `AWAITING_CONFIRMATION` (`isAsked && !confirmed`): `call_contact` is exposed for affirmation; `ask_user` is hidden to prevent confirmation loops.
- In `CONFIRMED`: `call_contact` is executed.

### Decision 3: Conservative Unique Exact Match (Optimization C)
In `lookup_contact`:
- Filter matches where `candidate.name.trim().equals(query.trim(), ignoreCase = true)`.
- If `exactMatches.size == 1`: Auto-select this candidate and advance state directly to `CALL_CONFIRMATION`.
- If `exactMatches.size != 1`: Enter `CONTACT_DISAMBIGUATION` with all retrieved candidate options.

### Decision 4: System Prompt Simplification
- Remove negative prompt directives like "Confirmation question was already asked" and "Do not invoke call_contact unless affirmed".
- Replace with concise semantic context:
  - In Disambiguation: *"Matching contacts: [c1] ... [c2] ... User is choosing a contact. Emit select_contact with the matching candidate_id."*
  - In Confirmation: *"Pending confirmation for calling <name>. Answer confirmation."*

## Risks / Trade-offs

- **[Risk] Model outputs invalid candidate ID in `select_contact`** → **Mitigation**: Kotlin validates `candidate_id in candidates`. If invalid, returns a single corrective tool result coaching the valid IDs.
- **[Risk] User declines during confirmation** → **Mitigation**: LLM produces conversational cancellation prose (`{"response": "Okay, cancelled"}`), task state clears, and mic closes cleanly.
- **[Risk] User says something completely unrelated during disambiguation** → **Mitigation**: Direct response prose is permitted for explicit cancellations or aborts.
