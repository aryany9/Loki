# Delta: conversation-manager

## ADDED Requirements

### Requirement: State-scoped tool and action grammar gating

The conversation layer and `GrammarBuilder` SHALL scope available tools and constrained decoding grammar strictly according to the active `TaskState` variant:
1. During `CONTACT_DISAMBIGUATION` (`taskState is ContactResolution` with `selectedId == null`), ONLY `select_contact` SHALL be exposed in the available tools and grammar. `ask_user`, `call_contact`, and `lookup_contact` SHALL be excluded.
2. During `CALL_CONFIRMATION` before the confirmation question is asked (`selectedId != null && !isAsked`), `call_contact` SHALL be excluded from the grammar, while `ask_user` remains available to generate the confirmation question.
3. During `AWAITING_CONFIRMATION` after the confirmation question is asked (`selectedId != null && isAsked && !confirmed`), `call_contact` SHALL be exposed in the grammar so affirmative responses can invoke it, while `ask_user` SHALL be excluded to prevent confirmation loops.
4. Only in the `CONFIRMED` state SHALL `call_contact` be executed.

#### Scenario: Disambiguation turn restricts grammar to selection
- **WHEN** a multi-match contact query triggers `CONTACT_DISAMBIGUATION`
- **THEN** the BNF grammar generated for the next turn permits only `select_contact(candidate_id)` and conversational responses
- **AND** `ask_user` and `call_contact` cannot be emitted by the model

#### Scenario: Awaiting confirmation exposes call_contact and hides ask_user
- **WHEN** a contact confirmation question has been asked to the user
- **THEN** the state is `AWAITING_CONFIRMATION` (`isAsked == true`)
- **AND** `call_contact` is present in the tool grammar while `ask_user` is excluded

#### Scenario: Selection transition advances to confirmation
- **WHEN** the model emits `select_contact(candidate_id: "c3")`
- **THEN** Kotlin validates that `c3` is in the active candidate list
- **AND** the state transitions to `CALL_CONFIRMATION` with `selectedId = "c3"`

### Requirement: Unique exact display-name pre-selection

When `lookup_contact` returns multiple contacts, the conversation manager SHALL inspect the candidate display names:
1. If exactly ONE candidate matches `candidate.name.trim().equals(query.trim(), ignoreCase = true)` and no duplicate exact-match exists, that candidate SHALL be automatically selected.
2. Automatic selection SHALL advance directly to `CALL_CONFIRMATION`, skipping `CONTACT_DISAMBIGUATION`.
3. If zero or multiple candidates have an exact display name match, the system SHALL enter `CONTACT_DISAMBIGUATION` with all candidates.

#### Scenario: Single exact match skips disambiguation
- **WHEN** the user says "Call Mom" and contacts include "Mom", "Suraj's Mom", "Prashik's Mom"
- **THEN** "Mom" is identified as the unique exact match
- **AND** state advances directly to confirmation for "Mom" without asking the user to choose

#### Scenario: Multiple identical names enter disambiguation
- **WHEN** the user says "Call Mom" and contacts include two distinct contacts both named "Mom"
- **THEN** exact-match count is 2 (not unique)
- **AND** the system enters `CONTACT_DISAMBIGUATION` with masked phone number suffixes
