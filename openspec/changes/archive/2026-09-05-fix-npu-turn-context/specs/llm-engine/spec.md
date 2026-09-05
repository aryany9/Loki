# Spec: LLM Engine — Context-Preserving KV Reset (delta)

## ADDED Requirements

### Requirement: KV-overflow handling is context-preserving

When the active conversation's token usage approaches the engine KV capacity, the engine MAY reset the conversation, but the reset MUST preserve the system instruction (re-created from the original `AgentConfig`) and MUST replay the most recent conversation turns within a bounded replay budget. A context-free reset (no config, no replay) is prohibited except as a logged last resort when replay cannot fit.

#### Scenario: Reset preserves system instruction and recent turns
- **GIVEN** an active multi-turn conversation nearing KV capacity
- **WHEN** the engine performs a KV reset during `generate()`
- **THEN** the new conversation is created with the original `AgentConfig` (system instruction intact)
- **AND** the most recent turns are replayed within the replay budget before the new prompt

#### Scenario: Last-resort reset is observable
- **GIVEN** a conversation whose recent turns exceed the replay budget
- **WHEN** the engine performs a KV reset
- **THEN** the conversation is re-created with the `AgentConfig` but without replay
- **AND** the reset and dropped context are logged/surfaced as an event

### Requirement: NPU KV capacity must not exceed the AOT graph's real context

When the active backend is NPU, the engine KV capacity used by compaction/reset guards SHALL be clamped to the AOT graph's real context (conservative default until container metadata provides the exact value), never the generic requested capacity (e.g. 8192).

#### Scenario: Compaction guard on NPU uses clamped capacity
- **GIVEN** an NPU-active conversation with requested KV capacity 8192 but an AOT graph context of ~1280
- **WHEN** token usage grows
- **THEN** the context-preserving reset triggers based on the clamped capacity
- **AND** the native runtime is not driven past its graph limit before a reset occurs

### Requirement: Voice activation replays recent context

On a new voice activation that creates a fresh engine conversation, the most recent turns of the previous voice conversation SHALL be replayed within the same bounded replay budget used for KV compaction, so assistant memory is continuous across voice activations.

#### Scenario: Follow-up across voice activations
- **GIVEN** a previous voice session where the user asked about a contact
- **WHEN** the user activates the assistant again and says "call her"
- **THEN** the new conversation's first prompt includes the replayed recent turns plus tool schemas

