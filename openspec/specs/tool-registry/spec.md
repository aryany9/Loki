## Purpose
Registry of available tools and their argument schemas for tool-call execution.

## Requirements

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

---

### Requirement: `ToolRegistry` is the authoritative source for available tools
The system SHALL maintain a `ToolRegistry` that holds all registered tools. The `ConversationSession`, `GrammarBuilder`, and `ToolRouter` SHALL consult the `ToolRegistry` exclusively. `ToolRegistry.getAvailableTools` SHALL accept optional `activeCapability`, `advancingTool`, and `taskState` parameters and SHALL return only tools that satisfy ALL of: granted permissions, satisfied environment availability (e.g. online tools excluded offline), capability scope, and task state legality (e.g. `select_contact` is exposed only during `CONTACT_DISAMBIGUATION`). Unavailable tools SHALL be omitted from the callable set — not exposed for runtime rejection — and SHALL appear in the disabled-tools notice when the user might plausibly request them.

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

---

### Requirement: Semantic argument validation before tool execution
`ToolRegistry` SHALL validate the arguments of every tool call against the tool's parameter schema before calling `Tool.execute()`.

#### Scenario: Empty required argument rejected
- **WHEN** the LLM produces `{"tool": "call_contact", "arguments": {"phone_number": ""}}`
- **THEN** `ToolRegistry` rejects the call with a validation error

#### Scenario: Valid arguments pass validation
- **WHEN** the LLM produces a valid tool call with all required arguments present
- **THEN** `ToolRegistry` validates the arguments successfully and delegates execution

---

### Requirement: Permission gating in `ToolRegistry`
`ToolRegistry.execute()` SHALL check permission state via `PermissionManager` and SHALL return a `ToolExecutionResult` sealed type. When a required permission is missing, the result SHALL be `ToolExecutionResult.PermissionRequired(permission: String, state: PermissionState)` — with `state` being either `REQUESTABLE` or `PERMANENTLY_DENIED` — rather than a generic error string.

#### Scenario: Permission not granted — requestable
- **WHEN** `call_contact` is invoked but `CALL_PHONE` is `REQUESTABLE`
- **THEN** `ToolRegistry` returns `ToolExecutionResult.PermissionRequired(permission="CALL_PHONE", state=REQUESTABLE)`
- **AND** the UI shows a permission rationale dialog with a "Grant" button

#### Scenario: Permission permanently denied
- **WHEN** `lookup_contact` is invoked but `READ_CONTACTS` is `PERMANENTLY_DENIED`
- **THEN** `ToolRegistry` returns `ToolExecutionResult.PermissionRequired(permission="READ_CONTACTS", state=PERMANENTLY_DENIED)`
- **AND** the UI shows a dialog directing the user to App Settings

---

### Requirement: `ToolResult` is structured
Every `Tool.execute()` call SHALL return a `ToolResult` containing: `success: Boolean`, `errorCode: ToolErrorCode?`, `data: Map<String, String>?`. Tools SHALL NOT return raw strings or generate natural-language responses.

#### Scenario: Successful tool returns structured data
- **WHEN** `LookupContactTool` executes successfully
- **THEN** the returned `ToolResult.data` contains structured contact information
- **AND** no natural-language text is included in the result

---

### Requirement: `LocalTool` and `OnlineTool` are explicitly separated
The system SHALL distinguish `LocalTool` (uses only on-device Android APIs) from `OnlineTool` (requires internet).

#### Scenario: Online tools excluded in offline mode
- **WHEN** the device has no internet connectivity
- **THEN** `ToolRegistry.getAvailableTools(offline=true)` excludes all `OnlineTool` implementations

---

### Requirement: Confirmation metadata is exposed by the registry
The `Tool` interface SHALL expose `requiresConfirmation: Boolean` (default `false`) and `describeAction(arguments): String`. `ToolRegistry` SHALL surface both for a given tool name so the conversation layer can gate execution without importing concrete tool classes.

#### Scenario: Registry reports confirmation requirement
- **WHEN** the conversation layer queries a registered gated tool
- **THEN** the registry reports `requiresConfirmation = true` and the tool-provided repeat-back for the parsed arguments

#### Scenario: Existing tools remain source-compatible
- **WHEN** the interface gains the new members with defaults
- **THEN** all previously registered tools compile and behave unchanged
