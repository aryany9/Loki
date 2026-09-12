# local-android-tools — Delta

## MODIFIED Requirements

### Requirement: CallContactTool initiates direct phone calls
`call_contact` SHALL be gated behind user confirmation: the tool SHALL declare `requiresConfirmation = true` and its repeat-back SHALL identify the contact name (when resolvable) and the full phone number before any `ACTION_CALL` intent is fired. No direct call SHALL be placed without an affirmative user verdict in the current interaction.

#### Scenario: Direct call is confirmed
- **WHEN** the user confirms the repeat-back "Call Rahul Sharma at +91 98765 43210?"
- **THEN** `ACTION_CALL` fires for the confirmed number

#### Scenario: Direct call is denied or unanswered
- **WHEN** the user denies the confirmation, or the confirmation times out
- **THEN** no call is placed
- **AND** the model is informed the user declined or did not respond

### Requirement: DialNumberTool opens the dialer without gating
`dial_number` SHALL NOT require confirmation because `ACTION_DIAL` only pre-fills the dialer; the user still places the call manually.

#### Scenario: Dialer pre-fill
- **WHEN** the model invokes `dial_number`
- **THEN** the dialer opens with the number pre-filled and no confirmation step occurs
