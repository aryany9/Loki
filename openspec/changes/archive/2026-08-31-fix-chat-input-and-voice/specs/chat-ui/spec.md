## MODIFIED Requirements

### Requirement: Inline voice input fallback in chat interface
The chat interface SHALL provide an inline microphone button allowing the user to record voice input directly within the chat screen. The mic path SHALL use the capability-driven voice strategy resolver (per the `voice-strategy-resolution` capability): audio-capable models SHALL send recorded audio directly to the LLM; text-only models SHALL transcribe offline via the STT engine before sending; unavailability SHALL be surfaced visibly in the chat UI.

#### Scenario: User taps mic button in chat
- **WHEN** the user taps the mic button in the chat input bar
- **THEN** voice input follows the resolved strategy — direct audio to an audio-capable LLM, or VAD capture transcribed via the STT engine for text-only models
- **AND** the result (audio turn or recognized transcript) is sent as a user message in the chat

#### Scenario: STT failure is surfaced
- **WHEN** voice input fails (model unavailable, STT not ready, recording error)
- **THEN** the chat UI displays a visible error message; the failure is never silently ignored
