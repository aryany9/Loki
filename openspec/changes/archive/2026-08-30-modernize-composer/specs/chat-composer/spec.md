## ADDED Requirements

### Requirement: Floating pill composer replaces bordered input field
The chat input SHALL render as a borderless, rounded pill container (background from theme tokens, no outline) containing a multiline text field that grows with content up to a maximum height and scrolls internally beyond it.

#### Scenario: Pill appearance
- **WHEN** the chat screen is displayed
- **THEN** the input area is a rounded pill with no visible text-field border, styled from `MaterialTheme`/token values

#### Scenario: Multiline growth
- **WHEN** the user types more than one line of text
- **THEN** the pill grows with the content up to a maximum height, after which the text field scrolls internally

### Requirement: Morphing action button driven by conversation state
The composer SHALL present exactly one action button whose appearance and behavior follow the state priority: (1) while a response is generating — a stop button that cancels generation; (2) while recording voice — a stop button that ends recording; (3) when the input has text — a send button; (4) otherwise — a mic button. Transitions between states SHALL be animated.

#### Scenario: Send state
- **WHEN** the input field contains text and no generation or recording is active
- **THEN** the button shows a send affordance; tapping sends the message and clears the input

#### Scenario: Mic state
- **WHEN** the input is empty and no generation or recording is active
- **THEN** the button shows a mic affordance; tapping starts voice input (existing behavior)

#### Scenario: Stop-recording state
- **WHEN** voice recording is in progress
- **THEN** the button shows a stop affordance; tapping stops recording (existing behavior)

### Requirement: Generation cancellation preserves partial output
While a response is streaming, the action button SHALL cancel the in-flight generation. Cancellation SHALL stop token generation (invoking the engine's cancel API), finalize the assistant message with the partial text streamed so far, and return the composer to the idle state.

#### Scenario: User stops a streaming response
- **WHEN** the user taps the stop button while the assistant message is streaming
- **THEN** generation stops, the assistant message keeps the partial text received so far with no streaming/thinking indicators, and the button returns to the send/mic state

#### Scenario: Cancel during tool execution
- **WHEN** the user taps stop while a tool is executing
- **THEN** the turn is aborted; the in-flight message is finalized showing any tool result received so far and the partial text (if any)
