# Delta: conversation-manager

## ADDED Requirements

### Requirement: Model-first language interpretation boundary

The app layer (AssistantSession, ConversationSession, ConversationManager) SHALL NOT
interpret natural-language user input. Keyword verdict sets, ordinal parsers, suffix
matchers, or any other NLU-by-code SHALL NOT exist in the voice path. Resolution of user
replies ("yes", "haan karo call", "just mom", "the first one") belongs exclusively to the
LLM, provided with sufficient context per the in-activation continuity requirement. The
app enforces safety sequencing (confirmation flow ordering, sanitization) only.

#### Scenario: Natural affirmative in any language

- **WHEN** a pending confirmation exists and the user replies "Haan, karo call"
- **THEN** the utterance is carried to the model verbatim (audio or transcript)
- **AND** the model decides the outcome; no app-side keyword matching occurs

### Requirement: ask_user turn-intent protocol

The tool registry SHALL include a no-side-effect `ask_user(text)` tool available to voice
and chat turns. When the model requires information or a decision from the user, it SHALL
end its turn by invoking `ask_user`. `ConversationSession` SHALL emit a
`ConversationEvent.AskUser(text)` and complete the turn. The `text` argument SHALL be the
speech-facing question (ID-free per the model-readable/speech-facing boundary
requirement).

#### Scenario: Model requests the floor

- **WHEN** the model ends its turn with `ask_user("Which contact would you like to call?")`
- **THEN** the text is spoken via TTS and the microphone re-arms upon TTS completion

### Requirement: In-activation pending-state continuity

Within one assistant activation, the ConversationManager SHALL maintain pending-task state
(`pendingAsk`: the question text, the model-readable options with candidate ids and masked
suffixes) that survives per-turn voice session recreation (consistent with the candidate
registry lifetime). Each follow-up turn's task-state block SHALL include the pending
question, the presented options, and the user's verbatim reply, with guidance that the
model resolve the reply itself. `pendingAsk` SHALL be cleared when the task completes, on
capture timeout, and on assistant session close. A NEW activation SHALL start with empty
pending state (cross-activation amnesia preserved).

#### Scenario: Disambiguation continuity

- **WHEN** turn 1 lists contact options via ask_user and turn 2's user reply is "just mom"
- **THEN** turn 2's prompt contains the pending question, the id-tagged options, and the
  verbatim reply "just mom"
- **AND** the model resolves the reply and may emit `call_contact` with the resolved
  candidate_id without re-asking

#### Scenario: Cross-activation amnesia intact

- **WHEN** a NEW assistant activation begins after a previous activation's disambiguation
  flow was abandoned
- **THEN** pendingAsk is empty and the first turn has no pending question in its context

## MODIFIED Requirements

### Requirement: Contact-call resolution precedes verbal confirmation and candidate registry survives voice turns

Tool results and task-state blocks — text the model reads but TTS never speaks — SHALL
include candidate ids and masked phone-number suffixes (e.g. `[c3] Mom — ending in 95`).
User-facing speech text — `ask_user` arguments, final DirectResponse — SHALL NOT contain
candidate ids. Full phone numbers SHALL NOT appear in either. The app SHALL NOT interpret
user replies: resolution of selections and confirmations belongs exclusively to the LLM,
provided with in-activation pending-state context.

#### Scenario: Model can bind a candidate

- **WHEN** the tool result for a multi-match `call_contact` is rendered
- **THEN** it contains `[cN]` ids and masked suffixes for each option
- **AND** the speech-facing options string contains neither ids nor full numbers

#### Scenario: User reply is interpreted by the model, not the app

- **WHEN** a pending contact selection or confirmation exists and the user replies in
  natural language (e.g. "just mom", "haan karo call")
- **THEN** the reply is carried to the model verbatim with the pending-state context
- **AND** no app-side keyword matching, ordinal parsing, or verdict parsing occurs

