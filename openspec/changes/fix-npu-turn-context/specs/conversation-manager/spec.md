# Spec: Conversation Manager — Compact Tool Prompting (delta)

## MODIFIED Requirements

### Requirement: Tool schema injection is once per conversation segment

Full tool schema text SHALL be injected into the prompt only on the first turn of a conversation segment (and after any conversation reset). Follow-up turns SHALL carry the user message and compact turn context without re-injecting full schemas. Capability-scoped grammar (constrained decoding) SHALL continue to apply on every turn where tools are available — grammar and schema injection are distinct concerns.

#### Scenario: Follow-up turn does not repeat schemas
- **GIVEN** an active multi-turn conversation with tools available
- **WHEN** the second and subsequent turns are built
- **THEN** the prompt contains no full tool-schema re-injection
- **AND** the scoped grammar is still applied for constrained decoding

#### Scenario: Reset re-injects schemas
- **GIVEN** a conversation reset (KV compaction)
- **WHEN** the next turn is built
- **THEN** full tool schemas are injected again (new conversation segment)

### Requirement: Backend-aware default output budget

The default `maxOutputTokens` when unspecified SHALL be backend-aware: 256 when the active engine backend is NPU, 512 otherwise.

#### Scenario: Default budget on NPU
- **GIVEN** the engine reports NPU as the active backend and no explicit `maxOutputTokens`
- **WHEN** a turn is generated
- **THEN** the default output budget is 256

### Requirement: Contact-call resolution precedes verbal confirmation

For `call_contact`, placeholder phone values (e.g. `"..."`, values without digits or shorter than 5 digits) SHALL be treated as invalid, triggering contact resolution before any verbal-confirmation gate. The confirmation question SHALL reference the RESOLVED contact. Session-level candidate state (id → contact) SHALL survive DirectResponse turns and engine context compactions until the call completes or the session closes, and `candidate_id` SHALL be resolved against it before falling back to a name re-query.

#### Scenario: Hallucinated phone number triggers lookup
- **GIVEN** the model emits `call_contact(phone_number: "...", name: "Mom")`
- **WHEN** the session resolves arguments
- **THEN** the placeholder is treated as invalid and `lookup_contact` runs for "Mom"
- **AND** the confirmation question names the resolved contact, not an unresolved one

#### Scenario: Candidate id survives conversational turns
- **GIVEN** a previous turn listed candidates c1–c8 and a later DirectResponse turn occurred
- **WHEN** the user says "third one" and the model emits `call_contact(candidate_id: "c3")`
- **THEN** the session resolves c3 from retained candidate state without re-querying by name

