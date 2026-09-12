## MODIFIED Requirements

### Requirement: `Tool` interface defines the contract for all assistant capabilities
The system SHALL define a `Tool` interface that every assistant capability (local or online) implements. The interface SHALL expose: a unique name, a human-readable description, a parameter schema, a `capability: String` domain identifier (default `"general"`), and an `execute` function that returns a structured `ToolResult`. A tool MAY be declared `general` only if it is state-free (reads/writes no task state), context-free (meaningful during any other capability's active task), and non-committal (never starts a multi-turn task). Domain tools SHALL declare their domain, never `general` by default.

#### Scenario: Tool executed by ToolRegistry
- **WHEN** `ToolRegistry.execute(toolName, arguments)` is called
- **THEN** the matching `Tool.execute(arguments)` is invoked
- **AND** a `ToolExecutionResult` is returned containing success/failure status and structured data

#### Scenario: General tools satisfy the governance rule
- **WHEN** the set of tools declaring `capability = "general"` is enumerated
- **THEN** every member is state-free, context-free, and non-committal (e.g. `remember_fact`, `search_chat_history`, `get_current_time`, `get_battery_status`)
- **AND** no domain tool (contact calling, timers, media, device toggles) declares `general`

### Requirement: `ToolRegistry` is the authoritative source for available tools
The system SHALL maintain a `ToolRegistry` that holds all registered tools. The `ConversationSession`, `GrammarBuilder`, and `ToolRouter` SHALL consult the `ToolRegistry` exclusively. `ToolRegistry.getAvailableTools` SHALL accept an optional `activeCapability` parameter and SHALL return only tools that satisfy ALL of: granted permissions, satisfied environment availability (e.g. online tools excluded offline), and capability scope (tools whose `capability` is `"general"` or equal to `activeCapability`; all permission-granted tools when no capability is active). Unavailable tools SHALL be omitted from the callable set — not exposed for runtime rejection — and SHALL appear in the disabled-tools notice when the user might plausibly request them.

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
