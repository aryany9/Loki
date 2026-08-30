## Purpose
Registry of available tools and their argument schemas for tool-call execution.

## Requirements

### Requirement: `Tool` interface defines the contract for all assistant capabilities
The system SHALL define a `Tool` interface that every assistant capability (local or online) implements. The interface SHALL expose: a unique name, a human-readable description, a parameter schema, and an `execute` function that returns a structured `ToolResult`.

#### Scenario: Tool executed by ToolRegistry
- **WHEN** `ToolRegistry.execute(toolName, arguments)` is called
- **THEN** the matching `Tool.execute(arguments)` is invoked
- **AND** a `ToolExecutionResult` is returned containing success/failure status and structured data

---

### Requirement: `ToolRegistry` is the authoritative source for available tools
The system SHALL maintain a `ToolRegistry` that holds all registered tools. The `ConversationSession`, `GrammarBuilder`, and `ToolRouter` SHALL consult the `ToolRegistry` exclusively.

#### Scenario: Tool registered at startup
- **WHEN** the application initializes
- **THEN** all MVP tools are registered in `ToolRegistry`
- **AND** `ToolRegistry.getAll()` returns the complete list

#### Scenario: Unknown tool call rejected
- **WHEN** the LLM produces a tool call for a tool name not in `ToolRegistry`
- **THEN** the registry rejects the call with an error result

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
