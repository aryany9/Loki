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
On voice sources, destructive actions SHALL be confirmed conversationally by the LLM. The system prompt SHALL instruct the model to state the contact name and full phone number in a question before invoking a gated tool (e.g. `call_contact`) and to invoke it only after the user's verbal affirmation. The user's natural reply ("yes, you are right", "sure", "no") SHALL be carried back to the model as audio (direct-audio) or transcript (STT-transcribe), and the model SHALL decide whether the user confirmed. No keyword matcher or regex verdict parsing SHALL be used on the voice confirmation path.

#### Scenario: Voice confirmation round-trip
- **WHEN** the user asks to call a contact with an audio-capable model active and the model asks "Do you want me to call Mom at +91 79001 96495?"
- **THEN** the user's spoken reply is routed to the model as native audio
- **AND** when the model interprets the reply as affirmative, `call_contact` executes with the exact looked-up phone number

#### Scenario: Verbal denial on voice
- **WHEN** the user replies negatively (e.g. "no") to a spoken confirmation question
- **THEN** the model does not invoke the gated tool and responds conversationally

#### Scenario: First-attempt gated call on voice is blocked
- **WHEN** the model emits a gated tool call without having asked a confirmation question on a voice turn
- **THEN** the call is not executed and the model is coached to ask first

---

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
