## Purpose
Safety gating and user confirmation flow for destructive or irreversible on-device tool actions.
## Requirements
### Requirement: Tools declare destructive actions requiring confirmation
A tool that performs a destructive or irreversible user-facing action SHALL declare `requiresConfirmation = true` and SHALL provide a natural-language `describeAction(arguments)` repeat-back string identifying the concrete target (e.g. contact name and phone number). Tools without the declaration SHALL execute immediately as before.

#### Scenario: Gated tool is invoked
- **WHEN** the model emits a tool call for a tool with `requiresConfirmation = true`
- **THEN** the tool is NOT executed
- **AND** the conversation layer emits a confirmation-required event containing the tool name and the repeat-back string

#### Scenario: Ungated tool is invoked
- **WHEN** the model emits a tool call for a tool with `requiresConfirmation = false`
- **THEN** the tool executes immediately with no confirmation step

---

### Requirement: The conversation loop awaits an explicit verdict
When a confirmation is required on a chat/text surface (source `TEXT`), the conversation loop SHALL suspend before tool execution, emit the repeat-back to the active surface, and await a verdict through a single `respondToConfirmation(accepted: Boolean)` entry point. Only one confirmation SHALL be pending at a time. On voice sources (`VOICE`, `DIRECT_AUDIO`, `VOICE_FOLLOW_UP`), the loop SHALL NOT suspend on a confirmation channel and SHALL NOT emit a blocking confirmation-required event.

#### Scenario: User confirms in chat
- **WHEN** the user accepts the pending confirmation via the chat UI
- **THEN** the tool executes with its original arguments
- **AND** execution continues exactly as an ungated call would

#### Scenario: User denies in chat
- **WHEN** the user rejects the pending confirmation via the chat UI
- **THEN** no tool execution occurs
- **AND** a tool-result turn stating the user declined is appended so the model can respond conversationally

#### Scenario: Gated tool called on voice path
- **WHEN** the model emits a tool call for a tool with `requiresConfirmation = true` during a voice turn
- **THEN** the tool does NOT execute and no confirmation channel is opened
- **AND** a tool-result turn instructs the model to first ask the user a confirmation question and invoke the tool only after verbal confirmation

---

### Requirement: Conversational confirmation on voice is model-driven

On voice sources, destructive actions SHALL be confirmed conversationally by the LLM. Prior to the confirmation question being asked (`!isAsked`), the destructive execution tool (e.g. `call_contact`) SHALL NOT be exposed in the tool grammar, and the app/model SHALL ask the user a verbal confirmation question referencing the contact name and masked phone distinguisher (e.g. "the number ending in 95") in a question — via `ask_user` or conversational direct response. Once the question has been asked and the system awaits the user's answer (`isAsked == true`), `call_contact` SHALL be exposed in the grammar so that an affirmative response can invoke it and transition to `CONFIRMED`. The user's natural reply — in ANY language or phrasing ("yes, you are right", "haan karo call", "sure", "no") — SHALL be carried back to the model verbatim as audio (direct-audio) or transcript (STT-transcribe), and the model SHALL decide whether the user confirmed. No keyword matcher, regex verdict parsing, or app-side language interpretation SHALL be used anywhere on the voice path. **Full phone numbers SHALL NOT be spoken or rendered in model context; masked suffixes only.**

#### Scenario: Voice confirmation transition sequencing
- **WHEN** a contact is selected and the confirmation question is asked
- **THEN** the state transitions to awaiting confirmation (`isAsked = true`)
- **AND** upon affirmation, the model invokes `call_contact(candidate_id)` and transitions to `CONFIRMED`

#### Scenario: Denial leaves action unexecuted
- **WHEN** the user declines during confirmation
- **THEN** the model produces conversational cancellation text
- **AND** `call_contact` is never executed and task state is cleared

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

### Requirement: Unresolved confirmations time out and cancel safely
A pending confirmation SHALL auto-cancel after a bounded timeout, producing the same denial turn as an explicit rejection. Cancelling generation SHALL also resolve any pending confirmation as denied.

#### Scenario: No response before timeout
- **WHEN** the confirmation timeout elapses with no verdict
- **THEN** the pending confirmation is cancelled
- **AND** a tool-result turn stating the action was cancelled is appended

#### Scenario: Generation cancelled while awaiting
- **WHEN** the user cancels generation while a confirmation is pending
- **THEN** the pending confirmation is resolved as denied
- **AND** no tool execution occurs afterwards

