## MODIFIED Requirements

### Requirement: Voice session is always ephemeral
`ConversationManager.newVoiceSession()` SHALL return a `ConversationSession` configured with `ConversationMode.VOICE` and a fresh, empty `ConversationContext` with `maxTurns=1`. The `maxTurns=1` limit SHALL constrain each `ConversationSession` to a single LLM generation cycle to maintain compact KV cache; it SHALL NOT limit an overarching Voice Interaction to a single spoken exchange. `AssistantSession` SHALL orchestrate multi-round voice interactions across distinct, ephemeral `ConversationSession` instances.

#### Scenario: New voice invocation has no memory of previous sessions
- **WHEN** a voice session ends (dismissed or completed) and a new voice session is invoked
- **THEN** the new session's prompt contains no turns from any previous voice session

#### Scenario: Voice session does not share context with chat history
- **WHEN** the user has an active chat conversation and then invokes the voice assistant
- **THEN** the voice session prompt does not include any turns from the chat session

#### Scenario: Multi-round voice interaction uses fresh generation sessions
- **WHEN** a voice interaction requires follow-up user input (e.g. contact disambiguation)
- **THEN** `AssistantSession` coordinates the follow-up using a fresh `ConversationSession` with `maxTurns=1`
- **AND** task continuity is preserved via structured `TaskState` rather than accumulated prompt history

## ADDED Requirements

### Requirement: Explicit ConversationMode binding
Every `ConversationSession` SHALL be bound to an immutable `ConversationMode` (`VOICE` or `CHAT`) upon instantiation, determining its tool governance policy, prompt assembly profile, memory retrieval scope, and output token allocation.

#### Scenario: Chat session creation
- **WHEN** `ConversationManager.newChatSession()` is called
- **THEN** the returned session has `mode == ConversationMode.CHAT`
- **AND** applies the chat output token budget (up to 512 tokens)

#### Scenario: Voice session creation
- **WHEN** `ConversationManager.newVoiceSession()` is called
- **THEN** the returned session has `mode == ConversationMode.VOICE`
- **AND** applies the voice output token budget (up to 128 tokens)

### Requirement: Chat sessions never synthesize voice task state
The `ConversationSession` `init` block SHALL only synthesize `pendingVoiceConfirmation`, `ContactResolution`, or `activeCapability = "calling"` state when `mode == ConversationMode.VOICE && taskState == null`. In `CHAT` mode this block SHALL be a strict no-op regardless of any constructor arguments supplied.

#### Scenario: Chat session receives no voice task state
- **WHEN** `ConversationSession` is constructed with `mode == ConversationMode.CHAT`
- **THEN** the init block does NOT synthesize `pendingVoiceConfirmation` or set `activeCapability = "calling"`
- **AND** `taskState` remains `null` unless explicitly provided as a constructor argument
- **AND** no calling capability is activated on the session
