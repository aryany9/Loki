# Spec: Conversation Manager — Compact Tool Prompting (delta)

## ADDED Requirements

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

### Requirement: Contact-call resolution precedes verbal confirmation and candidate registry survives voice turns

For `call_contact`, placeholder phone values (e.g. `"..."`, values without digits or shorter than 5 digits) SHALL be treated as invalid, triggering contact resolution before any verbal-confirmation gate. The confirmation question SHALL reference the RESOLVED contact.

The contact candidate registry SHALL live at the `ConversationManager` level to survive across per-turn voice session re-creations within an activation as well as in-session KV context compactions until a call completes or the activation dismisses.

When resolving `call_contact`:
1. If a `candidate_id` is supplied, it SHALL be resolved against active task state or the candidate registry.
2. If `candidate_id` cannot be resolved, the session SHALL coach the model with a stale-selection error and SHALL NOT fall back to an unconstrained name re-query.
3. If no `candidate_id` is supplied, a name re-query SHALL only execute if no valid candidate state exists, and SHALL auto-resolve only if exactly 1 match is returned.

When multiple contacts share the same display name:
1. Disambiguation options presented to the model/user SHALL be enriched with app-rendered masked phone number suffixes (e.g. `Mom — number ending in 21`). Full phone numbers SHALL NEVER be injected into the prompt.
2. The SPEECH-FACING options list (the text the model is expected to speak, e.g. the `ask_user` argument and final DirectResponse) SHALL be ID-free (e.g. `Options: Suraj's Mom; Mom — number ending in 21; Dad`). Model-readable text (tool results, task-state blocks) SHALL include the `[cN]` id mapping so the model can emit `call_contact(candidate_id)`. The ID mapping SHALL NOT be spoken in user-facing audio.
3. The model SHALL coach the user to select by distinction or ordinal (e.g. "the first one").
4. Internal identifiers (e.g. `c1`, `c2`, "candidate ID") SHALL NEVER be spoken in user-facing audio or rendered in direct user speech.

#### Scenario: ID-free speech list with model-readable id map
- **GIVEN** a `call_contact` lookup returns multiple candidates including duplicates
- **WHEN** the session renders the tool result and task-state block
- **THEN** the model-readable text contains `[cN]` ids and masked suffixes for each option
- **AND** the speech-facing options string contains no ids and no full phone numbers

#### Scenario: Name-only call is resolved, not re-queried into a loop
- **GIVEN** a candidate list was presented in a prior follow-up round of the same activation
- **WHEN** the model emits `call_contact(name: "Mom")` without a candidate_id
- **THEN** the session resolves against the registry/task state where possible instead of re-asking the identical open question

### Requirement: Cross-activation replay is source-scoped

Cross-activation replay SHALL be scoped by turn source: a voice activation SHALL replay only turns with voice source (`VOICE`, `DIRECT_AUDIO`, `VOICE_FOLLOW_UP`), and SHALL NOT replay `TEXT` (chat) turns into a voice conversation. In-session compaction replay SHALL remain source-agnostic to preserve full conversation context.

#### Scenario: Chat turns are not replayed into a voice activation
- **GIVEN** a chat session recorded TEXT turns containing tool-call JSON in `recentTurns`
- **WHEN** a new voice activation calls `startConversation`
- **THEN** the replay input excludes TEXT turns
- **AND** voice-source turns are still replayed subject to the executedAction filter

#### Scenario: In-session compaction ignores source
- **GIVEN** an in-session conversation containing mixed-source context
- **WHEN** `compactAndResetConversationInternal` replays turns into the new KV context
- **THEN** the replay is not filtered by turn source

### Requirement: Pre-TTS output-sanity recovery

Before emitting any final direct response to TTS or the user, the session SHALL inspect the text for protocol artifacts (e.g. `<|tool_call`, `<|`, markdown code blocks, or raw JSON tool calls). If protocol artifacts are detected in a direct response, the text SHALL NOT be spoken; instead, a generic recovery message ("Sorry, I didn't catch that — could you say it again?") SHALL be substituted.

#### Scenario: Hallucinated phone number triggers lookup
- **GIVEN** the model emits `call_contact(phone_number: "...", name: "Mom")`
- **WHEN** the session resolves arguments
- **THEN** the placeholder is treated as invalid and `lookup_contact` runs for "Mom"
- **AND** the confirmation question names the resolved contact, not an unresolved one

#### Scenario: Candidate id survives across voice turns within activation
- **GIVEN** turn 1 returned duplicate candidates `c1`–`c8` and asked the user to clarify
- **AND** a new `ConversationSession` is created for turn 2 per voice statelessness
- **WHEN** the user says "the third one" and the model emits `call_contact(candidate_id: "c3", name: "Mom")`
- **THEN** the session resolves `c3` from the manager-level candidate registry without executing a second `lookup_contact` query
- **AND** successful call execution clears the candidate registry

#### Scenario: Unresolvable candidate id returns stale error without re-query loop
- **GIVEN** candidate `c9` is not present in the candidate registry
- **WHEN** the model emits `call_contact(candidate_id: "c9", name: "Mom")`
- **THEN** the session returns a stale-selection coach message and does NOT execute a duplicate `lookup_contact` query

#### Scenario: Malformed protocol output is sanitized before TTS
- **GIVEN** the model emits `<|tool_call>call: "call_contact(c3, null, \"Mom\")"`
- **WHEN** the response is prepared for speech
- **THEN** the output-sanity filter intercepts the protocol artifact and speaks the recovery message


