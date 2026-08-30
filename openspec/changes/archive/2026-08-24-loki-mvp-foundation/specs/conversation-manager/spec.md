## ADDED Requirements

### Requirement: Multi-turn bounded agent loop
The `ConversationManager` SHALL implement a bounded ReAct-style agent loop that supports multiple LLM–tool–result cycles within a single user turn. The loop SHALL have a configurable maximum iteration limit (default: 5) to prevent runaway tool execution.

#### Scenario: Single-step request executes and responds
- **WHEN** the user asks "What time is it?"
- **THEN** the LLM produces a single tool call (`get_time`)
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
- **AND** `ConversationManager` delivers an error response to the user via TTS (e.g., "I wasn't able to complete that.")
- **AND** no further tool calls are made

---

### Requirement: Fast path for simple single-tool requests
`ConversationManager` SHALL support a fast path that skips a second LLM generation pass when the tool result is unambiguous and a deterministic response template is sufficient.

#### Scenario: Battery query uses fast path
- **WHEN** the user asks "What's my battery percentage?" and `get_battery_status` returns `{"level": 72}`
- **THEN** Loki responds "Battery is at 72 percent" using a template response
- **AND** no second LLM generation pass is performed

---

### Requirement: Conversation context window budget tracking
`ConversationManager` SHALL track the estimated token count of the conversation history and SHALL trim old tool-result entries before the context budget is exhausted.

#### Scenario: Context budget exceeded
- **WHEN** the accumulated conversation history approaches the model's context limit
- **THEN** `ConversationManager` trims the oldest tool result entries from history
- **AND** the system prompt and the most recent user turn are always preserved

---

### Requirement: Full cancellation support
`ConversationManager` SHALL support cancellation of the entire current pipeline (STT, LLM inference, tool execution, TTS) at any point during a turn.

#### Scenario: User interrupts mid-response
- **WHEN** the assistant is speaking a TTS response and the user invokes the assistant again
- **THEN** TTS playback is cancelled
- **AND** the pipeline resets to the listening state for the new utterance
- **AND** the cancelled turn is not replayed

---

### Requirement: Tool execution errors are handled gracefully
When a tool returns a `ToolResult` with `success=false`, `ConversationManager` SHALL handle the error by either retrying (if appropriate), asking the user for clarification, or informing the user that the action could not be completed.

#### Scenario: Tool execution fails with permission error
- **WHEN** `CallContactTool` fails due to `CALL_PHONE` permission not granted
- **THEN** `ConversationManager` triggers an Android permission request dialog
- **AND** upon grant, retries the tool call
- **AND** upon denial, informs the user that calling requires the permission

---

### Requirement: Session reset on new invocation
`ConversationManager` SHALL reset conversation state (turn history, tool loop state, pending coroutines) when a new `VoiceInteractionSession` starts.

#### Scenario: New session starts fresh
- **WHEN** the user invokes Loki again after a completed or cancelled session
- **THEN** `ConversationManager` starts with an empty turn history
- **AND** no state from the previous session leaks into the new session
