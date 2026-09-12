## ADDED Requirements

### Requirement: Centralized access policy gating in ToolRegistry
`ToolRegistry.executeDetailed()` SHALL evaluate `ToolAccessPolicy` against the current `DeviceLockState` from `DeviceLockStateProvider` before verifying permissions, validating arguments, or dispatching execution. If the policy returns `AccessDecision.Deny`, `ToolRegistry.executeDetailed()` SHALL immediately return `ToolExecutionResult.AccessDenied(decision)` without checking runtime permissions or invoking `Tool.execute()`.

#### Scenario: Tool access denied by policy
- **WHEN** `ToolRegistry.executeDetailed()` is invoked for a tool
- **AND** `ToolAccessPolicy.evaluate()` returns `AccessDecision.Deny`
- **THEN** `ToolRegistry.executeDetailed()` returns `ToolExecutionResult.AccessDenied`
- **AND** runtime permission checks and tool execution are bypassed

#### Scenario: Tool access allowed by policy
- **WHEN** `ToolRegistry.executeDetailed()` is invoked for a tool
- **AND** `ToolAccessPolicy.evaluate()` returns `AccessDecision.Allow`
- **THEN** `ToolRegistry.executeDetailed()` proceeds to permission verification and parameter validation

---

### Requirement: ToolExecutionResult represents access denial
`ToolExecutionResult` SHALL include a `data class AccessDenied(val decision: AccessDecision.Deny)` variant, and `ToolErrorCode` SHALL include `ACCESS_DENIED`. When `ToolRegistry.execute()` is invoked and access is denied, it SHALL return `ToolResult.error` with `ToolErrorCode.ACCESS_DENIED`.

#### Scenario: Detailed execution yields AccessDenied variant
- **WHEN** a tool execution is denied by access policy in `executeDetailed()`
- **THEN** the result is `ToolExecutionResult.AccessDenied` containing the denial decision

#### Scenario: Simple execution yields error with ACCESS_DENIED code
- **WHEN** `ToolRegistry.execute()` is called and access policy denies the tool
- **THEN** the returned `ToolResult.success` is `false`
- **AND** `ToolResult.errorCode` is `"ACCESS_DENIED"`
