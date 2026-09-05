## Purpose
Management of LLM and tool coordination with scoped conversation sessions.
## Requirements
### Requirement: Multi-turn bounded agent loop
The `ConversationSession` SHALL implement a bounded ReAct-style agent loop that supports multiple LLM–tool–result cycles within a single user turn. The loop SHALL have a configurable maximum iteration limit (default: 5) to prevent runaway tool execution.

#### Scenario: Single-step request executes and responds
- **WHEN** the user asks "What time is it?"
- **THEN** the LLM produces a single tool call (`get_current_time`)
- **AND** the tool executes and returns the time
- **AND** the result is delivered to the user via TTS without unnecessary additional LLM passes

#### Scenario: Multi-step clarification loop executes correctly
- **WHEN** the user says "Call Rahul" and the contact lookup returns two matches
- **THEN** the LLM asks "Which Rahul — Sharma or Verma?"
- **AND** the user's clarification is captured and fed back into the loop
- **AND** the correct contact is called

#### Scenario: Max iterations reached
- **WHEN** the agent loop reaches `maxIterations` tool calls without a final response
- **THEN** the loop terminates
- **AND** `ConversationSession` delivers an error response to the user (e.g., "I wasn't able to complete that.")
- **AND** no further tool calls are made

---

### Requirement: Fast path for simple single-tool requests
`ConversationSession` SHALL support a fast path that skips a second LLM generation pass when the tool result is unambiguous and a deterministic response template is sufficient.

#### Scenario: Battery query uses fast path
- **WHEN** the user asks "What's my battery percentage?" and `get_battery_status` returns `{"level": 72}`
- **THEN** Loki responds "Battery is at 72 percent" using a template response
- **AND** no second LLM generation pass is performed

---

### Requirement: Conversation context window budget tracking
`ConversationSession` SHALL track the estimated token count of the conversation history and SHALL trim old tool-result entries before the context budget is exhausted.

#### Scenario: Context budget exceeded
- **WHEN** the accumulated conversation history approaches the model's context limit
- **THEN** `ConversationSession` trims the oldest tool result entries from history
- **AND** the system prompt and the most recent user turn are always preserved

---

### Requirement: Full cancellation support
`ConversationSession` SHALL support cancellation of the entire current pipeline at any point during a turn.

#### Scenario: User interrupts mid-response
- **WHEN** the assistant is speaking a TTS response and the user invokes the assistant again
- **THEN** TTS playback is cancelled
- **AND** the pipeline resets to the listening state for the new utterance
- **AND** the cancelled turn is not replayed

---

### Requirement: Tool execution errors are handled gracefully
When a tool returns a `ToolResult` with `success=false`, `ConversationSession` SHALL handle the error by either retrying (if appropriate), asking the user for clarification, or informing the user that the action could not be completed.

#### Scenario: Tool execution fails with permission error
- **WHEN** `CallContactTool` fails due to `CALL_PHONE` permission not granted
- **THEN** `ConversationSession` triggers an Android permission request dialog
- **AND** upon grant, retries the tool call
- **AND** upon denial, informs the user that calling requires the permission

---

### Requirement: Session reset on new invocation
`ConversationManager` SHALL expose `newChatSession(): ConversationSession` and `newVoiceSession(): ConversationSession` as the primary entry points for processing utterances. Direct use of a shared `ConversationContext` is removed from the public API.

- `newChatSession()` SHALL return a `ConversationSession` backed by a persistent `ConversationContext` (up to `maxTurns=10`). Calling this method multiple times from the same `ChatViewModel` instance SHALL return the same underlying session (the context is held on the ViewModel, not the Manager).
- `newVoiceSession()` SHALL return a new `ConversationSession` with a fresh, empty `ConversationContext` (`maxTurns=1`) on every call. Each voice turn SHALL call this method, discarding any prior voice session.

#### Scenario: New voice session starts fresh
- **WHEN** `AssistantSession.startTurn()` calls `conversationManager.newVoiceSession()`
- **THEN** the returned session contains no turns from any previous interaction

#### Scenario: Chat session persists across messages
- **WHEN** `ChatViewModel` calls `chatSession.processUtterance()` twice in the same session
- **THEN** the second call's prompt includes the first call's user turn and assistant response

---

### Requirement: System prompt includes tool descriptions and disabled-tool notices
The system prompt SHALL be split into a KV-prefilled core prompt and per-turn composed content. The core prompt SHALL contain persona, the JSON output protocol, the language directive, and memories — and SHALL NOT contain tool schemas or capability instructions. The per-turn composition SHALL include: (1) the schema list of the currently visible tools (all permission-granted tools when no capability is active; `general` + active-capability tools when one is), (2) capability instructions injected in full on the activation turn and as a compact reminder while active, (3) a task-state block rendered fresh from the session's `TaskState` when present, and (4) disabled-tool notices where relevant. Nothing derived from runtime task state SHALL be baked into the KV-prefilled core prompt.

#### Scenario: Available tools appear in per-turn composition
- **WHEN** `get_current_time` and `get_battery_status` are available and no capability is active
- **THEN** the per-turn composition includes descriptive entries for both tools

#### Scenario: Scoped tool schemas during an active capability
- **WHEN** the `calling` capability is active
- **THEN** the per-turn composition lists only `general` and `calling` tool schemas
- **AND** the KV-prefilled core prompt remains unchanged

#### Scenario: Task state rendered fresh each turn
- **WHEN** a `ContactResolution` task state is pending with 6 candidates
- **THEN** the per-turn composition contains the candidate names and app-issued IDs
- **AND** no phone numbers appear anywhere in the model's context

#### Scenario: Disabled tool appears in notice with reason
- **WHEN** `CALL_PHONE` is not granted
- **THEN** the composition lists `call_contact` under "Disabled tools: Requires CALL_PHONE permission"

---

### Requirement: Capability activation and deactivation lifecycle
`ConversationSession` SHALL track an `activeCapability` derived from the model's tool calls: calling (or receiving a result from) a tool of capability C with no conflicting pending task state activates C; the capability deactivates when its task state completes (or is absent and a final response is produced) or on cancellation. No keyword matching, intent classifier, or separate routing model SHALL be used. Capability instructions SHALL be injected into the per-turn composition on activation.

#### Scenario: Tool call activates its capability
- **WHEN** the model emits `lookup_contact` with no capability active
- **THEN** the `calling` capability activates
- **AND** the next per-turn composition carries the calling instructions and scoped tool set

#### Scenario: Task completion deactivates
- **WHEN** the active capability's task state resolves and the model produces a final response
- **THEN** the capability deactivates
- **AND** the next turn's composition returns to the full tool set with core prompt only

#### Scenario: Pending state blocks silent capability switch
- **WHEN** a task state is unresolved and the model emits a tool call of a different capability
- **THEN** the call is not executed
- **AND** a coached tool-result turn directs the model to resolve the current task first

---

### Requirement: Application-owned task state with validated tool calls
`ConversationSession` SHALL own a typed task state (sealed `TaskState`) for multi-turn tool flows, starting with contact resolution. Each state variant SHALL expose `advancingTool: String?` derived from its own fields — the tool that can resolve or complete the state now — and `resolved: Boolean`. The application SHALL validate every advancing tool call against the live state (ID membership, confirmation prerequisites) before execution, and SHALL resolve identities (e.g. phone numbers) from its own data, never from model output. Phone numbers and other sensitive application facts SHALL NOT be placed in model context during task state. Task-state completion SHALL deactivate the capability.

#### Scenario: Candidate selection validated
- **WHEN** the model selects a candidate ID during `ContactResolution`
- **THEN** the session accepts it only if the ID matches a live candidate
- **AND** an invalid ID produces a corrective tool-result turn re-rendering the state

#### Scenario: Advancing tool remains callable while state is pending
- **WHEN** a task state is pending
- **THEN** the state's `advancingTool` is included in the scoped visible tool set
- **AND** the model always has a legal next move

#### Scenario: Call executes with app-resolved identity
- **WHEN** the user verbally confirms and the model emits the advancing call tool
- **THEN** the session validates the confirmed state, resolves the contact ID to the real phone number from its own lookup data, and executes `call_contact`
- **AND** the spoken response names the contact, never reading the raw number

### Requirement: ConversationStore exposes keyword search over turns
`ConversationStore` SHALL provide `searchTurns(query, limit)`: a case-insensitive substring search across persisted user and assistant turn texts, skipping corrupt conversation files without failing, returning matches with conversation id, title, matched snippet, and timestamp.

#### Scenario: Corrupt file skipped during search
- **WHEN** one conversation file is corrupt and others contain matches
- **THEN** matches from the valid files are returned
- **AND** the corrupt file is logged and skipped

---

### Requirement: System prompt carries the response-language directive
`buildSystemPrompt` SHALL append exactly one language directive derived from `AgentConfig.conversationLanguage`: `"auto"` instructs the model to respond in the same language the user writes or speaks; an explicit tag instructs it to always respond in that language (display name). The directive SHALL be positioned so it cannot displace safety or tool-signature prompt content.

#### Scenario: Mirrored response language
- **WHEN** `conversationLanguage = "auto"` and the user writes in Spanish
- **THEN** the system prompt instructs responding in the user's language
- **AND** the assistant responds in Spanish (within the loaded model's ability)

#### Scenario: Locked response language
- **WHEN** `conversationLanguage = "fr"`
- **THEN** the system prompt instructs always responding in French regardless of input language

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

Tool results and task-state blocks — text the model reads but TTS never speaks — SHALL
include candidate ids and masked phone-number suffixes (e.g. `[c3] Mom — ending in 95`).
User-facing speech text — `ask_user` arguments, final DirectResponse — SHALL NOT contain
candidate ids. Full phone numbers SHALL NOT appear in either. The app SHALL NOT interpret
user replies: resolution of selections and confirmations belongs exclusively to the LLM,
provided with in-activation pending-state context.

#### Scenario: Model can bind a candidate

- **WHEN** the tool result for a multi-match `call_contact` is rendered
- **THEN** it contains `[cN]` ids and masked suffixes for each option
- **AND** the speech-facing options string contains neither ids nor full numbers

#### Scenario: User reply is interpreted by the model, not the app

- **WHEN** a pending contact selection or confirmation exists and the user replies in
  natural language (e.g. "just mom", "haan karo call")
- **THEN** the reply is carried to the model verbatim with the pending-state context
- **AND** no app-side keyword matching, ordinal parsing, or verdict parsing occurs

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

### Requirement: Model-first language interpretation boundary

The app layer (AssistantSession, ConversationSession, ConversationManager) SHALL NOT
interpret natural-language user input. Keyword verdict sets, ordinal parsers, suffix
matchers, or any other NLU-by-code SHALL NOT exist in the voice path. Resolution of user
replies ("yes", "haan karo call", "just mom", "the first one") belongs exclusively to the
LLM, provided with sufficient context per the in-activation continuity requirement. The
app enforces safety sequencing (confirmation flow ordering, sanitization) only.

#### Scenario: Natural affirmative in any language

- **WHEN** a pending confirmation exists and the user replies "Haan, karo call"
- **THEN** the utterance is carried to the model verbatim (audio or transcript)
- **AND** the model decides the outcome; no app-side keyword matching occurs

### Requirement: ask_user turn-intent protocol

The tool registry SHALL include a no-side-effect `ask_user(text)` tool available to voice
and chat turns. When the model requires information or a decision from the user, it SHALL
end its turn by invoking `ask_user`. `ConversationSession` SHALL emit a
`ConversationEvent.AskUser(text)` and complete the turn. The `text` argument SHALL be the
speech-facing question (ID-free per the model-readable/speech-facing boundary
requirement).

#### Scenario: Model requests the floor

- **WHEN** the model ends its turn with `ask_user("Which contact would you like to call?")`
- **THEN** the text is spoken via TTS and the microphone re-arms upon TTS completion

### Requirement: In-activation pending-state continuity

Within one assistant activation, the ConversationManager SHALL maintain pending-task state
(`pendingAsk`: the question text, the model-readable options with candidate ids and masked
suffixes) that survives per-turn voice session recreation (consistent with the candidate
registry lifetime). Each follow-up turn's task-state block SHALL include the pending
question, the presented options, and the user's verbatim reply, with guidance that the
model resolve the reply itself. `pendingAsk` SHALL be cleared when the task completes, on
capture timeout, and on assistant session close. A NEW activation SHALL start with empty
pending state (cross-activation amnesia preserved).

#### Scenario: Disambiguation continuity

- **WHEN** turn 1 lists contact options via ask_user and turn 2's user reply is "just mom"
- **THEN** turn 2's prompt contains the pending question, the id-tagged options, and the
  verbatim reply "just mom"
- **AND** the model resolves the reply and may emit `call_contact` with the resolved
  candidate_id without re-asking

#### Scenario: Cross-activation amnesia intact

- **WHEN** a NEW assistant activation begins after a previous activation's disambiguation
  flow was abandoned
- **THEN** pendingAsk is empty and the first turn has no pending question in its context

---

### Requirement: State-scoped tool and action grammar gating

The conversation layer and `GrammarBuilder` SHALL scope available tools and constrained decoding grammar strictly according to the active `TaskState` variant:
1. During `CONTACT_DISAMBIGUATION` (`taskState is ContactResolution` with `selectedId == null`), ONLY `select_contact` SHALL be exposed in the available tools and grammar. `ask_user`, `call_contact`, and `lookup_contact` SHALL be excluded.
2. During `CALL_CONFIRMATION` before the confirmation question is asked (`selectedId != null && !isAsked`), `call_contact` SHALL be excluded from the grammar, while `ask_user` remains available to generate the confirmation question.
3. During `AWAITING_CONFIRMATION` after the confirmation question is asked (`selectedId != null && isAsked && !confirmed`), `call_contact` SHALL be exposed in the grammar so affirmative responses can invoke it, while `ask_user` SHALL be excluded to prevent confirmation loops.
4. Only in the `CONFIRMED` state SHALL `call_contact` be executed.

#### Scenario: Disambiguation turn restricts grammar to selection
- **WHEN** a multi-match contact query triggers `CONTACT_DISAMBIGUATION`
- **THEN** the BNF grammar generated for the next turn permits only `select_contact(candidate_id)` and conversational responses
- **AND** `ask_user` and `call_contact` cannot be emitted by the model

#### Scenario: Awaiting confirmation exposes call_contact and hides ask_user
- **WHEN** a contact confirmation question has been asked to the user
- **THEN** the state is `AWAITING_CONFIRMATION` (`isAsked == true`)
- **AND** `call_contact` is present in the tool grammar while `ask_user` is excluded

#### Scenario: Selection transition advances to confirmation
- **WHEN** the model emits `select_contact(candidate_id: "c3")`
- **THEN** Kotlin validates that `c3` is in the active candidate list
- **AND** the state transitions to `CALL_CONFIRMATION` with `selectedId = "c3"`

---

### Requirement: Unique exact display-name pre-selection

When `lookup_contact` returns multiple contacts, the conversation manager SHALL inspect the candidate display names:
1. If exactly ONE candidate matches `candidate.name.trim().equals(query.trim(), ignoreCase = true)` and no duplicate exact-match exists, that candidate SHALL be automatically selected.
2. Automatic selection SHALL advance directly to `CALL_CONFIRMATION`, skipping `CONTACT_DISAMBIGUATION`.
3. If zero or multiple candidates have an exact display name match, the system SHALL enter `CONTACT_DISAMBIGUATION` with all candidates.

#### Scenario: Single exact match skips disambiguation
- **WHEN** the user says "Call Mom" and contacts include "Mom", "Suraj's Mom", "Prashik's Mom"
- **THEN** "Mom" is identified as the unique exact match
- **AND** state advances directly to confirmation for "Mom" without asking the user to choose

#### Scenario: Multiple identical names enter disambiguation
- **WHEN** the user says "Call Mom" and contacts include two distinct contacts both named "Mom"
- **THEN** exact-match count is 2 (not unique)
- **AND** the system enters `CONTACT_DISAMBIGUATION` with masked phone number suffixes

