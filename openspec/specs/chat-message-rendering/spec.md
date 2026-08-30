## Purpose
Rendering of assistant messages: markdown, token streaming, thinking animation, and expandable tool-result cards.

## Requirements

### Requirement: Assistant messages render as Markdown
Assistant message text SHALL render as Markdown in the chat surface — supporting headings, bold/italic, unordered/ordered lists, inline code, and fenced code blocks (monospace, visually distinct background). Rendering SHALL derive colors and typography from `MaterialTheme`. User messages SHALL render as plain text.

#### Scenario: Assistant response contains code block
- **WHEN** the assistant replies with a fenced ``` code block
- **THEN** the block renders monospace in a visually distinct container with the surrounding prose rendered normally

#### Scenario: Rendering follows theme
- **WHEN** the theme changes from dark to light
- **THEN** markdown-rendered content (text, code blocks, links) immediately reflects the new color scheme

### Requirement: Token-by-token streaming display
While the model generates a response, the in-progress assistant message SHALL render incrementally as tokens arrive (via `ConversationEvent.GeneratingToken`), without waiting for the completed response. Token updates SHALL be batched to avoid excessive recomposition, and only the streaming message's UI SHALL update.

#### Scenario: Response streams progressively
- **WHEN** the user sends a message and the model begins generating
- **THEN** the assistant message appears and grows token-by-token until generation completes

#### Scenario: High-frequency tokens do not jank the list
- **WHEN** the engine emits many tokens per second
- **THEN** UI updates are batched and scroll position/other list items are not disrupted

### Requirement: Animated thinking indicator
While the request is processing and no content has streamed yet (`ConversationEvent.Thinking`), the assistant message row SHALL display an animated thinking indicator (pulsing dots or shimmer text) instead of a static progress spinner.

#### Scenario: Waiting for first token
- **WHEN** a request is processing and zero tokens have arrived
- **THEN** an animated thinking indicator is shown on the assistant row

#### Scenario: Indicator yields to content
- **WHEN** the first token arrives
- **THEN** the thinking indicator is replaced by the streaming message content

### Requirement: Tool results render as expandable cards
A tool execution attached to an assistant message SHALL render as a card with an always-visible header (tool name and success/error indication) and a tap-to-expand body revealing the result payload. The card SHALL replace the previous static "Action executed" pill.

#### Scenario: User expands a tool result
- **WHEN** the user taps a tool-result card header
- **THEN** the card expands in place to show the tool's result data; tapping again collapses it

#### Scenario: Failed tool execution is distinguishable
- **WHEN** a tool execution fails
- **THEN** the card header indicates the failure via error styling
