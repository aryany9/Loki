# Delta: tool-registry

## MODIFIED Requirements

### Requirement: `ToolRegistry` is the authoritative source for available tools
The system SHALL maintain a `ToolRegistry` that holds all registered tools. The `ConversationSession`, `GrammarBuilder`, and `ToolRouter` SHALL consult the `ToolRegistry` exclusively. `ToolRegistry.getAvailableTools` SHALL accept an optional `activeCapability`, `advancingTool`, and `taskState` parameter and SHALL return only tools that satisfy ALL of: granted permissions, satisfied environment availability (e.g. online tools excluded offline), capability scope, and task state legality (e.g. `select_contact` is exposed only during `CONTACT_DISAMBIGUATION`). Unavailable tools SHALL be omitted from the callable set — not exposed for runtime rejection — and SHALL appear in the disabled-tools notice when the user might plausibly request them.

#### Scenario: Tool registered at startup
- **WHEN** the application initializes
- **THEN** all MVP tools are registered in `ToolRegistry`
- **AND** `ToolRegistry.getAll()` returns the complete list

#### Scenario: Unknown tool call rejected
- **WHEN** the LLM produces a tool call for a tool name not in `ToolRegistry`
- **THEN** the registry rejects the call with an error result

#### Scenario: Scoped visibility while a capability is active
- **WHEN** the `calling` capability is active and `getAvailableTools` is queried
- **THEN** the result contains only `general` tools and `calling` tools (e.g. `lookup_contact`, `call_contact`, `dial_number`)
- **AND** tools of other capabilities (timers, media, device toggles) are absent

#### Scenario: Full visibility with no active capability
- **WHEN** no capability is active
- **THEN** all permission-granted, environment-available tools are returned regardless of capability

#### Scenario: Out-of-scope tool call corrected, not executed
- **WHEN** the model emits a tool call for a tool outside the scoped set during an active capability
- **THEN** the tool is not executed
- **AND** the conversation layer returns a corrective tool-result turn (coached deferral) naming the tool as currently unavailable
