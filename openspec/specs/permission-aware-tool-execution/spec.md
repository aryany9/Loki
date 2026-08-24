### Requirement: PermissionManager provides three-state permission checking
`PermissionManager` SHALL provide a method `checkPermission(context, permission): PermissionState` returning one of `GRANTED`, `REQUESTABLE`, or `PERMANENTLY_DENIED` for any given Android permission string.

#### Scenario: Permission is granted
- **WHEN** `PackageManager.checkPermission()` returns `PERMISSION_GRANTED` for a given permission
- **THEN** `PermissionManager.checkPermission()` returns `PermissionState.GRANTED`

#### Scenario: Permission is requestable
- **WHEN** the permission has not been granted and `shouldShowRequestPermissionRationale()` returns `true` OR the permission has never been requested before
- **THEN** `PermissionManager.checkPermission()` returns `PermissionState.REQUESTABLE`

#### Scenario: Permission is permanently denied
- **WHEN** the permission has not been granted and `shouldShowRequestPermissionRationale()` returns `false` (user tapped "Don't ask again")
- **THEN** `PermissionManager.checkPermission()` returns `PermissionState.PERMANENTLY_DENIED`

### Requirement: GrammarBuilder filters tools by permission state
`GrammarBuilder.buildFrom(toolRegistry, context)` SHALL only include tools in the GBNF schema for which all required permissions return `PermissionState.GRANTED`. Tools with any non-granted permission SHALL be excluded from the grammar.

#### Scenario: Tool with missing permission excluded from grammar
- **WHEN** `call_contact` requires `CALL_PHONE` and that permission is `REQUESTABLE`
- **THEN** the generated GBNF grammar does not include `call_contact` as a valid tool enum value

#### Scenario: Tool with granted permission included in grammar
- **WHEN** `get_current_time` requires no permissions
- **THEN** the generated GBNF grammar includes `get_current_time` as a valid tool enum value

### Requirement: System prompt includes disabled tool explanations
The system prompt constructed by `ConversationManager` SHALL include a section listing tools that are disabled due to missing permissions, with the reason stated.

#### Scenario: Disabled tool appears in prompt with reason
- **WHEN** `CALL_PHONE` permission is not granted
- **THEN** the system prompt contains a "Disabled tools" section listing `call_contact` with the text "Requires CALL_PHONE permission"

#### Scenario: LLM generates a helpful response for disabled tool intent
- **WHEN** the user says "Call Mom" and `call_contact` is excluded from the grammar
- **THEN** the LLM cannot output a `call_contact` JSON object and instead generates a `{"response": "..."}` explaining the permission is needed

### Requirement: ToolExecutionResult communicates permission state
`ToolRegistry.execute()` SHALL return a sealed `ToolExecutionResult` type. When a required permission is missing it SHALL return `ToolExecutionResult.PermissionRequired(permission, state: PermissionState)` instead of a generic error string.

#### Scenario: Tool execution returns permission state
- **WHEN** a tool is executed but a required permission is `REQUESTABLE`
- **THEN** the result is `ToolExecutionResult.PermissionRequired` with `state = REQUESTABLE`

#### Scenario: Permanently denied returns distinct result
- **WHEN** a required permission is `PERMANENTLY_DENIED`
- **THEN** the result is `ToolExecutionResult.PermissionRequired` with `state = PERMANENTLY_DENIED`
