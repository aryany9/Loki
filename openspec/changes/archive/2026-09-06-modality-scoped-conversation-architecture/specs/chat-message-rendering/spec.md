## MODIFIED Requirements

### Requirement: Tool results render as expandable cards
Each tool execution associated with an assistant message SHALL be tracked in `toolInvocations: List<ToolInvocation>` using a stable execution correlation `id: String`. Multi-tool turns SHALL render each tool execution card in chronological order beneath the independent Markdown message body without overwriting text or other tool cards. Single-tool properties (`toolResult`, `toolName`) on `ChatMessage` SHALL be deprecated compatibility accessors delegating to `toolInvocations.lastOrNull()`.

#### Scenario: User expands a tool result
- **WHEN** the user taps a tool-result card header
- **THEN** the card expands in place to show the tool's result data; tapping again collapses it

#### Scenario: Failed tool execution is distinguishable
- **WHEN** a tool execution fails
- **THEN** the card header indicates the failure via error styling

#### Scenario: Multiple tools in a single turn
- **WHEN** the assistant executes two tools during a single turn
- **THEN** both tool invocations are rendered as separate expandable cards below the assistant's Markdown text
- **AND** each card is keyed by its unique execution correlation `id`

## ADDED Requirements

### Requirement: Code Blocks In Direct Responses Do Not Trigger Tool Retries
The response parser SHALL identify direct natural language responses containing Markdown code blocks (including blocks containing programming language braces `{ ... }`) without JSON tool markers (`"tool":`) as valid `DirectResponse` instances, and SHALL NOT flag them as `Malformed` or trigger corrective retries.

#### Scenario: Assistant generates Java or Kotlin code block with braces
- **WHEN** the model outputs a Markdown explanation and a fenced code block with `{ ... }` braces without tool JSON
- **THEN** `ToolCallParser.parse()` returns `DirectResponse` containing the full un-truncated Markdown
- **AND** no corrective "Return JSON only" retry prompt is sent to the model
- **AND** the code block renders in the chat UI with monospace styling

### Requirement: Clean Real-Time Streaming Tokens
The token streaming pipeline SHALL sanitize intermediate tokens via `ToolCallParser.cleanStreamingPartial()` before emitting updates to the UI state. Partial outputs representing tool invocation JSON SHALL suppress text updates to prevent raw JSON flashes, while `{"response": "..."}` JSON envelopes SHALL be unwrapped on-the-fly to stream clean natural language without exposing wrapper JSON syntax.

#### Scenario: Tool call JSON is suppressed from streaming text
- **WHEN** the model streams tokens beginning with a tool call JSON structure
- **THEN** `ToolCallParser.cleanStreamingPartial()` returns `null`
- **AND** `ChatViewModel` maintains the thinking state without flashing raw JSON tokens in the message bubble

#### Scenario: Response envelope is unwrapped during streaming
- **WHEN** the model streams tokens formatted as `{"response": "Here is your answer..."}`
- **THEN** `ToolCallParser.cleanStreamingPartial()` strips the envelope prefix and trailing formatting
- **AND** the UI renders the conversational answer directly in Markdown

### Requirement: Modality-Scoped Protocol Artifact Sanitization
Protocol artifact detection SHALL be scoped by interaction modality: in `VOICE` mode, backticks (` ``` `) and standalone tool names SHALL trigger sanitization to ensure clean TTS audio output; in `CHAT` mode, backtick-fenced code blocks and natural mentions of tool names within prose SHALL be preserved.

#### Scenario: Code fences preserved in Chat mode
- **WHEN** a chat response contains backticks or code blocks
- **THEN** `containsProtocolArtifacts(text, ConversationMode.CHAT)` evaluates to `false`
- **AND** Markdown formatting is retained in the UI

#### Scenario: Bare tool name sanitized in Chat mode
- **WHEN** a chat response contains only a bare tool name (e.g. `ask_user`) with no accompanying prose
- **THEN** `containsProtocolArtifacts(text, ConversationMode.CHAT)` evaluates to `true`
- **AND** the bare artifact is sanitized

