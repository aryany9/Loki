## ADDED Requirements

### Requirement: Confirmation audio capture is gated on the TTS state
When a voice confirmation is required, the assistant SHALL arm a single microphone capture
that remains active through the spoken repeat-back, SHALL NOT commit samples captured while
the TTS engine is speaking as user input, and SHALL begin committing audio on the TTS
completion callback using a short rolling lookback (on the order of 120 to 180 ms) so a
promptly spoken reply is not clipped. End of speech SHALL be detected by the existing
energy VAD. The capture path SHALL NOT rely on a fixed wall-clock delay between TTS
completion and listening.

#### Scenario: User reply is captured after the repeat-back
- **WHEN** the overlay speaks the repeat-back and the user replies immediately after TTS
  completes
- **THEN** the reply audio is committed and transcribed as the verdict utterance
- **AND** no dead-air pause is inserted between TTS completion and listening

#### Scenario: TTS tail is not captured as user input
- **WHEN** the TTS engine is speaking the repeat-back or the re-prompt
- **THEN** audio captured during playback is not committed as user input
- **AND** the assistant's own speech does not produce a verdict or burn a listen attempt

### Requirement: Voice confirmation resolves within two listen attempts
The voice confirmation flow SHALL parse the committed transcript for an affirmative or
negative verdict, guarded against echo of the repeat-back and re-prompt text. An utterance
matching neither set SHALL trigger exactly one re-prompt ("Sorry, I didn't get that - yes
or no?") followed by one further listen attempt before falling back to the timeout path.

#### Scenario: Verbal yes resolves as accepted
- **WHEN** the committed transcript contains an affirmative keyword
- **THEN** the confirmation resolves as accepted and the tool executes

#### Scenario: Echoed re-prompt does not resolve the verdict
- **WHEN** the committed transcript matches the re-prompt text or another echo pattern
- **THEN** the verdict is unrecognized
- **AND** the flow proceeds to the re-prompt or timeout path without executing the tool

#### Scenario: Unrecognized reply re-prompts once
- **WHEN** the first listen attempt yields an unrecognized utterance
- **THEN** the overlay speaks the re-prompt and listens one more time
- **AND** a second unrecognized result falls through to the timeout path

### Requirement: Voice confirmation timeout produces a denial result for the model
The voice confirmation SHALL be bounded by the shared `CONFIRMATION_TIMEOUT_MS` (20
seconds). On expiry the gate SHALL resolve as denied and a denial tool-result SHALL be
recorded in the conversation context so the model can respond conversationally. The voice
path SHALL NOT terminate with a hardcoded "cancelled" message that bypasses the model.

#### Scenario: Timeout resolves as denial
- **WHEN** the confirmation timeout elapses without an accepted or denied verdict
- **THEN** the gate resolves as denied
- **AND** a denial tool-result is appended so the model can reply naturally
