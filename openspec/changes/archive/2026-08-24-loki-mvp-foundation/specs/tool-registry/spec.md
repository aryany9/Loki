## ADDED Requirements

### Requirement: `Tool` interface defines the contract for all assistant capabilities
The system SHALL define a `Tool` interface that every assistant capability (local or online) implements. The interface SHALL expose: a unique name, a human-readable description, a parameter schema, and an `execute` function that returns a structured `ToolResult`.

#### Scenario: Tool executed by ToolRegistry
- **WHEN** `ToolRegistry.execute(toolName, arguments)` is called
- **THEN** the matching `Tool.execute(arguments)` is invoked
- **AND** a `ToolResult` is returned containing success/failure status and structured data

---

### Requirement: `ToolRegistry` is the authoritative source for available tools
The system SHALL maintain a `ToolRegistry` that holds all registered tools. The `ConversationManager`, `GrammarBuilder`, and `ToolRouter` SHALL consult the `ToolRegistry` exclusively rather than referencing individual tools directly.

#### Scenario: Tool registered at startup
- **WHEN** the application initializes
- **THEN** all MVP tools are registered in `ToolRegistry`
- **AND** `ToolRegistry.getAll()` returns the complete list

#### Scenario: Unknown tool call rejected
- **WHEN** the LLM produces a tool call for a tool name not in `ToolRegistry`
- **THEN** the registry rejects the call with an error result
- **AND** the `ConversationManager` handles the error (asks for clarification or informs the user)

---

### Requirement: Semantic argument validation before tool execution
`ToolRegistry` SHALL validate the arguments of every tool call against the tool's parameter schema before calling `Tool.execute()`. Syntactically valid but semantically invalid arguments SHALL be rejected.

#### Scenario: Empty required argument rejected
- **WHEN** the LLM produces `{"tool": "call_contact", "arguments": {"name": ""}}`
- **THEN** `ToolRegistry` rejects the call with a validation error
- **AND** execution does not proceed

#### Scenario: Valid arguments pass validation
- **WHEN** the LLM produces `{"tool": "call_contact", "arguments": {"name": "Rahul"}}`
- **THEN** `ToolRegistry` validates the arguments successfully
- **AND** delegates execution to `CallContactTool`

---

### Requirement: Permission gating in `ToolRegistry`
`ToolRegistry` SHALL check whether required Android permissions are granted before executing a tool. If a required permission is missing, the registry SHALL return a `ToolResult` indicating a permission error rather than attempting execution.

#### Scenario: Permission not granted
- **WHEN** `CallContactTool` is invoked but `READ_CONTACTS` has not been granted
- **THEN** `ToolRegistry` returns a `ToolResult` with `success=false` and `reason=PERMISSION_DENIED`
- **AND** the `ConversationManager` requests the permission from the user

---

### Requirement: `ToolResult` is structured
Every `Tool.execute()` call SHALL return a `ToolResult` containing: `success: Boolean`, `errorCode: ToolErrorCode?`, `data: JsonObject?`. Tools SHALL NOT return raw strings or generate natural-language responses.

#### Scenario: Successful tool returns structured data
- **WHEN** `LookupContactTool` executes with `name="Rahul"` and finds two matches
- **THEN** the returned `ToolResult.data` contains a JSON array of contact objects with id and displayName fields
- **AND** no natural-language text is included in the result

---

### Requirement: `LocalTool` and `OnlineTool` are explicitly separated
The system SHALL distinguish `LocalTool` (uses only on-device Android APIs) from `OnlineTool` (requires internet). `ToolRegistry` SHALL be able to report which tools are available offline.

#### Scenario: Online tools excluded in offline mode
- **WHEN** the device has no internet connectivity
- **THEN** `ToolRegistry.getAvailableTools(offline=true)` excludes all `OnlineTool` implementations
- **AND** the LLM system prompt reflects only the currently available tools
