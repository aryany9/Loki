## ADDED REQUIREMENTS

### Requirement: Voice start cue on mic press
Pressing the chat mic to start voice input SHALL play a short attention tone as part of the voice start sequence (before STT begins); the composer mic/stop morphing behavior is otherwise unchanged.

#### Scenario: Mic press starts voice with audio cue
- **WHEN** the user presses the chat mic
- **THEN** the start tone plays and the voice recording path begins