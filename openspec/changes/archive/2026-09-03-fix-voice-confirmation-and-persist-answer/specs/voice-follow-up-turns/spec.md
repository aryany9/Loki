## ADDED Requirements

### Requirement: The voice session listens for a follow-up answer after asking a question
The assistant SHALL capture a follow-up spoken answer within the same voice invocation when
a voice turn ends with a question (a final response ending with "?"), using the TTS-gated
capture window, then transcribe it and feed it back as a new user turn on the same voice
session (source `VOICE_FOLLOW_UP`), instead of ending the turn after stating the question.
The loop SHALL be capped at 3 follow-up rounds. A final response that is not a question
SHALL be spoken once and the turn SHALL complete in the terminal completed state showing
that response.

#### Scenario: User answers a disambiguation question by voice
- **WHEN** the assistant asks "Which one would you like to call: Suraj's Mom, Mom, or Mom 2?"
- **THEN** the overlay shows the question with a listening indicator
- **AND** the user's spoken choice ("Suraj's Mom") is transcribed and processed as a new
  turn on the same session
- **AND** the resulting action (e.g. the call confirmation) proceeds without a new invocation

#### Scenario: Final non-question response ends the loop
- **WHEN** a follow-up round produces a response that does not end with "?"
- **THEN** the response is spoken once and the turn completes holding that response text

### Requirement: Follow-up capture retries once on silence, then completes gracefully
The assistant SHALL speak a short "I didn't catch that" prompt and retry capture once if a
follow-up round times out (20 s, the shared confirmation timeout) or captures empty audio.
If the retry also yields nothing, the loop SHALL exit gracefully with the last question
response shown in the terminal completed state — no error state, no hardcoded cancellation
message. The microphone SHALL be released on every exit path of the loop.

#### Scenario: Silence during follow-up
- **WHEN** the user does not respond to the follow-up question within the timeout, and the
  retry also yields silence
- **THEN** the assistant completes with the last question text shown
- **AND** no error is surfaced and the recorder is released

#### Scenario: Recorder released on cancellation
- **WHEN** the session is dismissed or the turn is cancelled mid-follow-up
- **THEN** the follow-up loop's cleanup releases the microphone unconditionally
