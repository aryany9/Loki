# voice-interaction-ui — Delta

## ADDED Requirements

### Requirement: Voice overlay captures verbal confirmation while remaining in Listening
When a confirmation is required during a voice interaction, the overlay SHALL speak the repeat-back, then return to the Listening state **without stopping the microphone**, capture the user's next utterance, and resolve the confirmation from it. A verbal affirmative ("yes" and equivalents) SHALL resolve as accepted; a verbal negative ("no", "cancel", "stop" and equivalents) SHALL resolve as denied. If the utterance matches neither set, the overlay SHALL re-prompt once before falling back to timeout.

#### Scenario: Verbal yes
- **WHEN** the overlay is awaiting a verdict and the transcript contains an affirmative keyword
- **THEN** the confirmation resolves as accepted and the tool executes
- **AND** the overlay continues its normal response flow

#### Scenario: Verbal no
- **WHEN** the overlay is awaiting a verdict and the transcript contains a negative keyword
- **THEN** the confirmation resolves as denied and the overlay speaks or displays the cancellation

#### Scenario: Unrelated utterance
- **WHEN** the transcript matches neither keyword set
- **THEN** the overlay re-prompts "Sorry — yes or no?" once
- **AND** a further non-matching utterance lets the confirmation time out

## MODIFIED Requirements

### Requirement: Session state indicator reflects the confirmation phase
The overlay SHALL render a distinct awaiting-confirmation visual state showing the repeat-back text prominently (with the live equalizer still reactive to voice), instead of the plain Listening indicator, so the user can see exactly what they are confirming.

#### Scenario: Awaiting verbal confirmation
- **WHEN** the overlay state is awaiting a verbal verdict
- **THEN** the repeat-back text is displayed prominently with the live equalizer active
- **AND** the state is visually distinguishable from plain Listening
