# Delta: action-confirmation

## MODIFIED Requirements

### Requirement: Conversational confirmation on voice is model-driven

On voice sources, destructive actions SHALL be confirmed conversationally by the LLM. Prior to the confirmation question being asked (`!isAsked`), the destructive execution tool (e.g. `call_contact`) SHALL NOT be exposed in the tool grammar, and the app/model SHALL ask the user a verbal confirmation question referencing the contact name and masked phone distinguisher. Once the question has been asked and the system awaits the user's answer (`isAsked == true`), `call_contact` SHALL be exposed in the grammar so that an affirmative response can invoke it and transition to `CONFIRMED`.

#### Scenario: Voice confirmation transition sequencing
- **WHEN** a contact is selected and the confirmation question is asked
- **THEN** the state transitions to awaiting confirmation (`isAsked = true`)
- **AND** upon affirmation, the model invokes `call_contact(candidate_id)` and transitions to `CONFIRMED`

#### Scenario: Denial leaves action unexecuted
- **WHEN** the user declines during `CALL_CONFIRMATION`
- **THEN** the model produces conversational cancellation text
- **AND** `call_contact` is never executed and task state is cleared
