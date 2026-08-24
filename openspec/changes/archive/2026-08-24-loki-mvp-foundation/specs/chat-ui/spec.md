## ADDED Requirements

### Requirement: Full-screen Jetpack Compose Chat Activity for direct launcher opens
When the user opens Loki directly from the application launcher (i.e. launching `MainActivity`), the system SHALL display a full-screen Jetpack Compose chat interface instead of the voice interaction overlay.

#### Scenario: User launches app from home screen
- **WHEN** the user opens Loki via the app launcher icon
- **THEN** `MainActivity` renders a chat screen with conversation history, text input field, send button, and an optional mic button
- **AND** the screen is not a transient overlay

---

### Requirement: Text-based conversation input and output
The chat interface SHALL allow the user to send messages via text input and receive assistant responses displayed as chat bubbles in a scrollable list.

#### Scenario: User sends text message
- **WHEN** the user types a prompt (e.g. "What time is it?" or "Turn on Bluetooth") and taps send
- **THEN** the message is added as a user bubble in the chat history
- **AND** the message text is forwarded to `ConversationManager.processUserInput(text)`
- **AND** the assistant response / tool execution result is appended to the chat history as an assistant bubble

---

### Requirement: Inline voice input fallback in chat interface
The chat interface SHALL provide an inline microphone button allowing the user to record voice input directly within the chat screen.

#### Scenario: User taps mic button in chat
- **WHEN** the user taps the mic button in the chat input bar
- **THEN** audio capture is started with VAD
- **AND** spoken audio is transcribed via `WhisperSttEngine`
- **AND** the recognized transcript is inserted and sent as a user message in the chat

---

### Requirement: TTS disabled by default in chat mode
In the chat interface, the assistant SHALL respond in text only by default and SHALL NOT automatically speak responses via `TtsEngine`.

#### Scenario: Assistant generates response in chat mode
- **WHEN** `ConversationManager` finishes processing a request in chat mode
- **THEN** the response is rendered visually in the chat UI
- **AND** `LocalTtsEngine` does not play audio automatically

---

### Requirement: In-memory session history in chat mode
The chat interface SHALL maintain an in-memory list of conversation turns for the active lifecycle of `MainActivity`. Persistent database storage is deferred to a future change.

#### Scenario: Conversation builds across multiple turns
- **WHEN** the user interacts with Loki across multiple back-and-forth messages in the chat screen
- **THEN** previous messages remain visible and scrollable in the chat history list during the current activity session
