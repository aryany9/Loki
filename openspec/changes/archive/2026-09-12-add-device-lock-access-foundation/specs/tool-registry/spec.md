## ADDED Requirements

### Requirement: Centralized access policy gating in ToolRegistry
`ToolRegistry.executeDetailed()` SHALL enforce execution preconditions in strict order:
1. Lookup tool by name. If not registered, return `ToolExecutionResult.Error` with `ToolErrorCode.NOT_FOUND`.
2. Evaluate `ToolAccessPolicy.evaluate(tool, arguments, deviceLockState)`. If the policy returns `AccessDecision.Deny`, immediately return `ToolExecutionResult.AccessDenied(decision)` without checking runtime permissions, validating arguments, or invoking `Tool.execute()`.
3. Check required permissions. If any permission is ungranted, return `ToolExecutionResult.PermissionRequired`.
4. Validate required arguments. If any required argument is missing or blank, return `ToolExecutionResult.Error` with `ToolErrorCode.VALIDATION_ERROR`.
5. Dispatch `tool.execute(context, arguments)`.

#### Scenario: Tool not registered
- **WHEN** `ToolRegistry.executeDetailed()` is invoked with an unknown tool name
- **THEN** it returns `ToolExecutionResult.Error` with `ToolErrorCode.NOT_FOUND`
- **AND** access policy evaluation is bypassed

#### Scenario: Tool access denied by policy
- **WHEN** `ToolRegistry.executeDetailed()` is invoked for a registered tool
- **AND** `ToolAccessPolicy.evaluate(tool, arguments, lockState)` returns `AccessDecision.Deny`
- **THEN** `ToolRegistry.executeDetailed()` returns `ToolExecutionResult.AccessDenied`
- **AND** runtime permission checks, argument validation, and tool execution are bypassed

#### Scenario: Tool access allowed by policy
- **WHEN** `ToolRegistry.executeDetailed()` is invoked for a registered tool
- **AND** `ToolAccessPolicy.evaluate(tool, arguments, lockState)` returns `AccessDecision.Allow`
- **THEN** `ToolRegistry.executeDetailed()` proceeds to permission verification and parameter validation

---

### Requirement: ToolExecutionResult represents access denial
`ToolExecutionResult` SHALL include a `data class AccessDenied(val decision: AccessDecision.Deny)` variant, and `ToolErrorCode` SHALL include `ACCESS_DENIED`. When `ToolRegistry.execute()` is invoked and access is denied, it SHALL return `ToolResult.error` with `ToolErrorCode.ACCESS_DENIED` and message derived from `decision.message ?: "Access denied: ${decision.reason}"`.

#### Scenario: Detailed execution yields AccessDenied variant
- **WHEN** a tool execution is denied by access policy in `executeDetailed()`
- **THEN** the result is `ToolExecutionResult.AccessDenied` containing the denial decision

#### Scenario: Simple execution yields error with ACCESS_DENIED code
- **WHEN** `ToolRegistry.execute()` is called and access policy denies the tool
- **THEN** the returned `ToolResult.success` is `false`
- **AND** `ToolResult.errorCode` is `"ACCESS_DENIED"`

---

### Requirement: Conversation turn termination on AccessDenied
When `ConversationSession` receives `ToolExecutionResult.AccessDenied` (either during the ReAct tool execution loop or during internal pre-lookups), it SHALL immediately terminate the tool execution loop without re-prompting the LLM (`break`), emit `ConversationEvent.ToolExecuted` containing a `ToolResult` with `ToolErrorCode.ACCESS_DENIED`, record the failure, and return deterministic assistant turn text (using `decision.message` or a default fallback based on `DenialReason`).

#### Scenario: ReAct tool execution denied by policy
- **WHEN** `executeDetailed()` returns `ToolExecutionResult.AccessDenied`
- **THEN** `ConversationSession` does not re-prompt the LLM
- **AND** emits `ConversationEvent.ToolExecuted` with `ToolErrorCode.ACCESS_DENIED`
- **AND** sets the assistant final response to the denial guidance and finishes the turn

#### Scenario: Pre-lookup tool execution denied by policy
- **WHEN** an internal pre-lookup in `ConversationSession` returns `ToolExecutionResult.AccessDenied`
- **THEN** `ConversationSession` does not proceed to action confirmation or execution
- **AND** terminates the turn immediately with denial guidance
