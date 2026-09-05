# voice-pipeline — Delta

## ADDED Requirements

### Requirement: STT transcribes in the configured language
`transcribeAudio` SHALL accept a language parameter defaulting to `"auto"` and SHALL pass it to the whisper bridge, where `"auto"` triggers language auto-detection. Both STT paths (chat STT provisioning and the assistant's STT_TRANSCRIBE strategy) SHALL pass the configured conversation language.

#### Scenario: Locked language transcribed
- **WHEN** `conversationLanguage = "hi"` and the user speaks Hindi in the STT_TRANSCRIBE path
- **THEN** the whisper call receives `"hi"` rather than the bridge's `"en"` default

#### Scenario: Auto-detect
- **WHEN** `conversationLanguage = "auto"`
- **THEN** the whisper call receives `"auto"` and detection runs per utterance

### Requirement: TTS speaks in the configured language
`AndroidTtsEngine` SHALL NOT pin a hardcoded locale. It SHALL expose `configureLanguage(bcp47Tag)`, apply it at init and on agent-config change, resolving `"auto"`/null to the device default locale and degrading without crash when the platform TTS lacks the requested voice.

#### Scenario: TTS follows the setting
- **WHEN** `conversationLanguage = "es"` and the assistant speaks a response
- **THEN** the TTS engine's locale is Spanish for that utterance

#### Scenario: Missing system voice degrades gracefully
- **WHEN** the configured language's voice is not installed on the device
- **THEN** TTS falls back to its default voice and logs a warning
- **AND** the app does not crash or silently drop the utterance

## MODIFIED Requirements

### Requirement: TTS engine initialization
`AndroidTtsEngine` SHALL resolve its initial TTS locale through `configureLanguage` (device default when unconfigured) instead of hardcoding `Locale.US`.

#### Scenario: Engine init without configuration
- **WHEN** the TTS engine initializes before any language setting is applied
- **THEN** it uses the device default locale rather than `Locale.US`
