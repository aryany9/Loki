# Delta: action-confirmation

## MODIFIED Requirements

### Requirement: Conversational confirmation on voice is model-driven

On voice sources, destructive actions SHALL be confirmed conversationally by the LLM. The
tool-result turn SHALL instruct the model to state the contact name and a MASKED phone
distinguisher (e.g. "the number ending in 95") in a question — via the `ask_user`
turn-intent protocol — before invoking a gated tool (e.g. `call_contact`), and to invoke it
only after the user's verbal affirmation. The user's natural reply — in ANY language or
phrasing ("yes, you are right", "haan karo call", "sure", "no") — SHALL be carried back to
the model verbatim as audio (direct-audio) or transcript (STT-transcribe), and the model
SHALL decide whether the user confirmed. No keyword matcher, regex verdict parsing, or
app-side language interpretation SHALL be used anywhere on the voice path. **Full phone
numbers SHALL NOT be spoken or rendered in model context; masked suffixes only.**

#### Scenario: Voice confirmation round-trip

- **WHEN** the user asks to call a contact and the model asks via `ask_user`
  "Shall I call Mom, the number ending in 95?"
- **THEN** the user's spoken reply is routed to the model as native audio
- **AND** when the model interprets the reply as affirmative, `call_contact` executes with
  the exact looked-up phone number

#### Scenario: Masked distinguisher replaces full number

- **WHEN** the model composes a voice confirmation question for a gated call
- **THEN** the spoken question contains the contact name and at most a masked suffix of
  the phone number
- **AND** the full phone number appears in neither the spoken text nor the model context

#### Scenario: Verbal denial on voice

- **WHEN** the user replies negatively (e.g. "no", "nahi, cancel") to a spoken confirmation
  question
- **THEN** the model does not invoke the gated tool and responds conversationally

#### Scenario: First-attempt gated call on voice is blocked

- **WHEN** the model emits a gated tool call without having asked a confirmation question
  on a voice turn
- **THEN** the call is not executed and the model is coached to ask first

#### Scenario: No double confirmation

- **WHEN** the model has already asked for confirmation and the user's reply is carried
  back with in-activation pending-state context
- **THEN** an affirmative reply results in immediate execution
- **AND** the app does not inject an additional confirmation prompt
