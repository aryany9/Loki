## MODIFIED Requirements

### Requirement: Response text displayed
The overlay SHALL display the text of Loki's response when the assistant is speaking via
TTS. After a turn completes successfully with a final response, the overlay SHALL continue
displaying that response text in a terminal completed state instead of reverting to an
empty "Ready" Idle label. The "Ready" label SHALL appear only when there is no prior answer
to show in the current session (no successful answered turn, or the turn ended in silence
or error). The persisted response SHALL clear when a new turn begins or the session
dismisses.

#### Scenario: Response text shown during TTS playback
- **WHEN** the `TtsEngine` begins speaking a response
- **THEN** the response text appears in the overlay
- **AND** the text remains visible until the session ends or transitions to the next
  listening state

#### Scenario: Response persists after the turn completes
- **WHEN** TTS finishes speaking the answer and the turn ends successfully
- **THEN** the final response text remains visible in a terminal completed state
- **AND** the overlay does not revert to the "Ready" Idle label

#### Scenario: Ready appears only without a prior answer
- **WHEN** a turn ends without an answer to show (silence, empty speech, or error)
- **THEN** the overlay may display the "Ready" Idle label

#### Scenario: Persisted response clears on the next turn
- **WHEN** a new turn begins or the session is dismissed
- **THEN** the persisted response is cleared and the state machine advances normally
