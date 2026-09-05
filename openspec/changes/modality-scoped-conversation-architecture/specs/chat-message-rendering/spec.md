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
