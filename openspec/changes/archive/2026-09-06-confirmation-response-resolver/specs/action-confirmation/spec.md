## MODIFIED Requirements

### Requirement: Conversational confirmation on voice is model-driven
On voice sources, destructive actions SHALL be confirmed through the `ConfirmationResolver` when `TaskState.expectedSemantics == CONFIRMATION`. The resolver is an isolated, grammar-constrained LLM inference call — it is NOT a keyword matcher, regex, or app-side language parser, and it satisfies the model-first language interpretation boundary. Prior to the confirmation question being asked (`!isAsked`), the destructive execution tool (e.g. `call_contact`) SHALL NOT be exposed in the tool grammar, and the app/model SHALL ask the user a verbal confirmation question referencing the contact name and masked phone distinguisher (e.g. "the number ending in 95") via `ask_user`. Once the question has been asked (`isAsked == true`), the user's natural reply — in ANY language or phrasing ("yes, you are right", "haan karo call", "sure", "no", "nahi", "mat karo") — SHALL be classified by `ConfirmationResolver` returning one of three outcomes: `CONFIRMED`, `DECLINED`, or `UNKNOWN`. The app SHALL perform a deterministic state transition based on the outcome without routing the utterance through the main conversational LLM path. No keyword matcher, regex verdict parsing, or app-side language interpretation SHALL be used anywhere on the voice path. **Full phone numbers SHALL NOT be spoken or rendered in model context; masked suffixes only.**

#### Scenario: CONFIRMED outcome advances state and unblocks execution
- **WHEN** `ConfirmationResolver` returns `CONFIRMED`
- **THEN** `ConversationManager.confirmContactResolution()` is called to advance `ContactResolution.confirmed = true`
- **AND** a new `ConversationSession` is created with the updated `taskState` where `call_contact` is unblocked in the grammar
- **AND** the model invokes `call_contact` in the next turn

#### Scenario: DECLINED outcome cancels action and clears state
- **WHEN** `ConfirmationResolver` returns `DECLINED`
- **THEN** `ConversationManager.clearVoiceTask()` is called to clear `taskState`, `pendingVoiceAsk`, and `pendingVoiceConfirmation`
- **AND** a cancellation TTS phrase is spoken
- **AND** the follow-up loop exits without invoking `call_contact`

#### Scenario: UNKNOWN outcome re-prompts without state change
- **WHEN** `ConfirmationResolver` returns `UNKNOWN`
- **THEN** no `TaskState` mutation occurs
- **AND** the follow-up loop re-arms the microphone
- **AND** the original confirmation question is re-spoken to the user
- **AND** the existing round-limit and retry-on-silence mechanisms apply

#### Scenario: Voice confirmation transition sequencing
- **WHEN** a contact is selected and the confirmation question is asked
- **THEN** the state transitions to awaiting confirmation (`isAsked = true`)
- **AND** upon affirmation, `ConfirmationResolver` returns `CONFIRMED`, `call_contact(candidate_id)` is executed, and the state transitions to `CONFIRMED`

#### Scenario: Denial leaves action unexecuted
- **WHEN** the user declines during confirmation
- **THEN** `ConfirmationResolver` returns `DECLINED`
- **AND** `call_contact` is never executed and task state is cleared

#### Scenario: Masked distinguisher replaces full number
- **WHEN** the model composes a voice confirmation question for a gated call
- **THEN** the spoken question contains the contact name and at most a masked suffix of the phone number
- **AND** the full phone number appears in neither the spoken text nor the model context

#### Scenario: First-attempt gated call on voice is blocked
- **WHEN** the model emits a gated tool call without having asked a confirmation question on a voice turn
- **THEN** the call is not executed and the model is coached to ask first

#### Scenario: No double confirmation
- **WHEN** the model has already asked for confirmation and the user's reply is carried back with in-activation pending-state context
- **THEN** an affirmative reply results in `ConfirmationResolver` returning `CONFIRMED` and immediate execution
- **AND** the app does not inject an additional confirmation prompt

## ADDED Requirements

### Requirement: `ConfirmationOutcome` is the sealed contract between resolver and follow-up loop
`core:conversation` SHALL define `enum class ConfirmationOutcome { CONFIRMED, DECLINED, UNKNOWN }`. `AssistantSession` SHALL import and branch exclusively on this enum. No string parsing, no integer codes, no boolean flags SHALL be used to communicate the resolver result to the follow-up loop.

#### Scenario: Resolver result drives follow-up loop branch
- **WHEN** `ConfirmationResolver.resolve()` returns
- **THEN** `AssistantSession` switches on `ConfirmationOutcome` with exactly three branches: `CONFIRMED`, `DECLINED`, `UNKNOWN`
- **AND** no default or fallthrough branch exists that could silently mishandle a fourth outcome
