## Purpose
Full-screen Jetpack Compose chat interface for direct launcher opens, with text/voice input and persistent conversation history.

## Requirements

### Requirement: Full-screen Jetpack Compose Chat Activity for direct launcher opens
When the user opens Loki directly from the application launcher (i.e. launching `MainActivity`), the system SHALL display a full-screen Jetpack Compose chat interface instead of the voice interaction overlay. The chat interface SHALL host the navigation drawer shell (per the `app-shell` capability) and SHALL NOT present inline navigation icon buttons in its top bar — destinations are reached via the drawer.

#### Scenario: User launches app from home screen
- **WHEN** the user opens Loki via the app launcher icon
- **THEN** `MainActivity` renders a chat screen with conversation history, drawer access, text input field, and the morphing action button
- **AND** the screen is not a transient overlay

---

### Requirement: Text-based conversation input and output
The chat interface SHALL allow the user to send messages via text input and receive assistant responses displayed in a scrollable list. User messages SHALL render as end-aligned bubbles; assistant responses SHALL render as full-width, bubble-less messages with Markdown formatting per the `chat-message-rendering` capability, streaming progressively as tokens are generated.

#### Scenario: User sends text message
- **WHEN** the user types a prompt (e.g. "What time is it?" or "Turn on Bluetooth") and taps send
- **THEN** the message is added as a user bubble in the chat history
- **AND** the message text is forwarded to `ConversationManager.processUserInput(text)`
- **AND** the assistant response is appended to the chat history as a full-width assistant message and streams in as generation progresses

#### Scenario: Assistant response renders full-width
- **WHEN** the assistant produces a response (with or without markdown content)
- **THEN** the response renders across the full message width without a tinted bubble background, while user messages remain in end-aligned bubbles

---

### Requirement: Inline voice input fallback in chat interface
The chat interface SHALL provide an inline microphone button allowing the user to record voice input directly within the chat screen. The mic path SHALL use the capability-driven voice strategy resolver (per the `voice-strategy-resolution` capability): audio-capable models SHALL send recorded audio directly to the LLM; text-only models SHALL transcribe offline via the STT engine before sending; unavailability SHALL be surfaced visibly in the chat UI.

#### Scenario: User taps mic button in chat
- **WHEN** the user taps the mic button in the chat input bar
- **THEN** voice input follows the resolved strategy — direct audio to an audio-capable LLM, or VAD capture transcribed via the STT engine for text-only models
- **AND** the result (audio turn or recognized transcript) is sent as a user message in the chat

#### Scenario: STT failure is surfaced
- **WHEN** voice input fails (model unavailable, STT not ready, recording error)
- **THEN** the chat UI displays a visible error message; the failure is never silently ignored

---

### Requirement: TTS disabled by default in chat mode
In the chat interface, the assistant SHALL respond in text only by default and SHALL NOT automatically speak responses via `TtsEngine`.

#### Scenario: Assistant generates response in chat mode
- **WHEN** `ConversationManager` finishes processing a request in chat mode
- **THEN** the response is rendered visually in the chat UI
- **AND** `LocalTtsEngine` does not play audio automatically

---

### Requirement: Persistent session history in chat mode
The chat interface SHALL operate on a durable, stored conversation: history SHALL survive app restarts via the `conversation-persistence` capability, the most recent conversation SHALL be restored at startup, and the user SHALL be able to start a new conversation from the chat surface. In-memory-only session history is no longer sufficient.

#### Scenario: Conversation builds across multiple turns
- **WHEN** the user interacts with Loki across multiple back-and-forth messages in the chat screen
- **THEN** previous messages remain visible and scrollable in the chat history list during the current activity session

#### Scenario: History survives restart
- **WHEN** the user completes exchanges, force-closes the app, and reopens it
- **THEN** the previous conversation's messages are restored in the chat screen and new messages append to the same conversation

---

### Requirement: Voice start cue on mic press
Pressing the chat mic to start voice input SHALL play a short attention tone as part of the voice start sequence (before STT begins); the composer mic/stop morphing behavior is otherwise unchanged.

#### Scenario: Mic press starts voice with audio cue
- **WHEN** the user presses the chat mic
- **THEN** the start tone plays and the voice recording path begins
