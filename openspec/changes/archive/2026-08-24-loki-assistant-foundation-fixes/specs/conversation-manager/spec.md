## MODIFIED Requirements

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

### Requirement: System prompt includes tool descriptions and disabled-tool notices
The system prompt constructed within a `ConversationSession` SHALL include:
1. A section listing all available (permission-granted) tools with name and one-line description.
2. A section listing all disabled tools (missing permission) with the tool name and the missing permission name.

The system prompt SHALL be rebuilt at the start of each turn to reflect current permission state.

#### Scenario: Available tools appear in prompt
- **WHEN** `get_current_time` and `get_battery_status` are available
- **THEN** the system prompt includes descriptive entries for both tools

#### Scenario: Disabled tool appears in prompt with reason
- **WHEN** `CALL_PHONE` is not granted
- **THEN** the system prompt lists `call_contact` under "Disabled tools: Requires CALL_PHONE permission"

## ADDED Requirements

### Requirement: Multi-turn bounded agent loop
The `ConversationSession` SHALL implement a bounded ReAct-style agent loop that supports multiple LLM–tool–result cycles within a single user turn. The loop SHALL have a configurable maximum iteration limit (default: 5) to prevent runaway tool execution.

#### Scenario: Single-step request executes and responds
- **WHEN** the user asks "What time is it?"
- **THEN** the LLM produces a single tool call (`get_current_time`)
- **AND** the tool executes and returns the time
- **AND** the result is delivered to the user via TTS without unnecessary additional LLM passes

#### Scenario: Max iterations reached
- **WHEN** the agent loop reaches `maxIterations` tool calls without a final response
- **THEN** the loop terminates
- **AND** `ConversationSession` delivers an error response to the user (e.g., "I wasn't able to complete that.")
- **AND** no further tool calls are made

### Requirement: Fast path for simple single-tool requests
`ConversationSession` SHALL support a fast path that skips a second LLM generation pass when the tool result is unambiguous and a deterministic response template is sufficient.

#### Scenario: Battery query uses fast path
- **WHEN** the user asks "What's my battery percentage?" and `get_battery_status` returns `{\"level\": 72}`
- **THEN** Loki responds "Battery is at 72 percent" using a template response
- **AND** no second LLM generation pass is performed

### Requirement: Conversation context window budget tracking
`ConversationSession` SHALL track the estimated token count of the conversation history and SHALL trim old tool-result entries before the context budget is exhausted.

#### Scenario: Context budget exceeded
- **WHEN** the accumulated conversation history approaches the model's context limit
- **THEN** `ConversationSession` trims the oldest tool result entries from history
- **AND** the system prompt and the most recent user turn are always preserved

### Requirement: Full cancellation support
`ConversationSession` SHALL support cancellation of the entire current pipeline at any point during a turn.

#### Scenario: User interrupts mid-response
- **WHEN** the assistant is speaking a TTS response and the user invokes the assistant again
- **THEN** TTS playback is cancelled
- **AND** the pipeline resets to the listening state for the new utterance
- **AND** the cancelled turn is not replayed
