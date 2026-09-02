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

