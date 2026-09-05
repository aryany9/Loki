# Delta: voice-pipeline

## MODIFIED Requirements

### Requirement: Multi-turn follow-up capture is strategy-aware

The assistant's follow-up loop SHALL route each captured follow-up utterance according to
the resolved voice-input strategy. On the STT-transcribe strategy the loop SHALL transcribe
before sending text; on the direct-audio strategy the loop SHALL convert the captured PCM
to WAV and send it as audio bytes with an empty user-input string. The loop SHALL NOT rely
on STT availability to process a captured utterance when the strategy is direct-audio. The
follow-up loop SHALL use TTS-gated microphone capture so the assistant's own spoken tail is
not ingested as user speech.

**Mic re-arm SHALL be driven exclusively by the model's structured turn-intent signal
(`ask_user` tool invocation). The app SHALL NOT inspect response prose to decide whether to
listen — response text shape (question marks, phrasing, punctuation) SHALL NOT trigger or
suppress microphone re-arm. The follow-up loop SHALL NOT be bounded by a small round cap; a
generous safety limit (>= 10 rounds) MAY exist to guard against runaway loops.**

#### Scenario: Direct-audio follow-up with Whisper inactive

- **WHEN** the active model is audio-capable (direct-audio), Whisper is not loaded, and the
  model ends its turn with `ask_user`
- **THEN** the captured reply is sent as WAV audio bytes with an empty user-input string

#### Scenario: STT-transcribe follow-up unchanged

- **WHEN** the active model is text-only (STT-transcribe) and the model ends its turn with
  `ask_user`
- **THEN** the captured reply is transcribed and sent as text

#### Scenario: Silent follow-up

- **WHEN** the follow-up capture contains no speech (silent buffer) under either strategy
- **THEN** the round expires via the confirmation timeout with a graceful sign-off and the
  pending state is cleared

#### Scenario: Question prose without intent signal

- **WHEN** the model's final response ends with a question mark but the turn did NOT end
  with an `ask_user` invocation
- **THEN** the microphone SHALL NOT re-arm and the turn completes in the terminal state
- **AND** a DEBUG-level diagnostic is logged so protocol adherence is observable

#### Scenario: Mid-string question

- **WHEN** the model's final response contains a question mid-string (e.g. "Which contact
  would you like to call? I see ... and Rushikesh's Mom.") and ends with `ask_user`
- **THEN** the microphone re-arms — text shape is irrelevant to the decision
