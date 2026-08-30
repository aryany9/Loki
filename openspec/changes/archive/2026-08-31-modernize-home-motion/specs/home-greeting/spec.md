## ADDED Requirements

### Requirement: Empty-conversation home state with greeting
When the active conversation contains no turns, the chat surface SHALL display a home state: a large-typography greeting (styled from theme tokens) and 3–4 tappable suggestion chips. The home state SHALL NOT render when a conversation has restored turns. The system SHALL NOT seed fake assistant greeting messages into conversations.

#### Scenario: Fresh conversation shows home state
- **WHEN** the user starts a new conversation
- **THEN** the chat surface shows the greeting and suggestion chips, and no fake assistant message appears in the history

#### Scenario: Restored conversation skips home state
- **WHEN** a conversation with existing turns is loaded
- **THEN** the chat history renders without the greeting/chips overlay

### Requirement: Suggestion chips send prompts
Tapping a suggestion chip SHALL initiate sending that suggestion as a user message (with light haptic feedback) and dismiss the home state as normal message flow takes over.

#### Scenario: Chip tap sends message
- **WHEN** the user taps a suggestion chip on the home state
- **THEN** the chip's text is sent as a user message and the assistant response flow begins
