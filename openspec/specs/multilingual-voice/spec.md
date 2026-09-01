# multilingual-voice Specification

## Requirements

### Requirement: Conversation language setting
`AgentConfig` SHALL carry `conversationLanguage` (default `"auto"`; value `"auto"` or a BCP-47 tag), persisted with the existing agent-config storage such that pre-change persisted configs deserialize unchanged. `"auto"` semantics: STT auto-detects per utterance, TTS uses the device default locale, and the model is instructed to mirror the user's language. An explicit tag locks all three to that language.

#### Scenario: User picks a language
- **WHEN** the user selects Hindi in Settings
- **THEN** `conversationLanguage` persists as `"hi"` and applies to new conversations without app restart

#### Scenario: Pre-change config deserializes
- **WHEN** a persisted AgentConfig JSON predates the field
- **THEN** it deserializes with `conversationLanguage = "auto"`

#### Scenario: Setting propagates everywhere
- **WHEN** the language is set and a new conversation starts
- **THEN** the same value drives STT transcription, TTS locale, and the response-language prompt directive
