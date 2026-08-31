## ADDED REQUIREMENTS

### Requirement: Audio start cue for voice input
When a voice input session begins (chat mic press or assistant long-press), the system SHALL play a short (~250 ms), non-speech, Gemini-like rising attention tone BEFORE microphone recording / listening begins. The cue SHALL be synthesized at runtime (no bundled asset), SHALL play exactly once per session, and SHALL NOT play when the session ends, when a turn completes, or on configuration changes. A hidden `audioStartCueEnabled` flag SHALL gate playback (default true).

#### Scenario: Chat mic plays start cue
- **WHEN** the user presses the chat mic
- **THEN** the attention tone plays before the STT/recording path starts

#### Scenario: Assistant long-press plays start cue
- **WHEN** the user long-presses the assistant trigger
- **THEN** the attention tone plays as the voice overlay begins listening

#### Scenario: Cue does not repeat or play on stop
- **WHEN** voice recording is already active or the session ends
- **THEN** no additional tone is played