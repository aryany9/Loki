## MODIFIED Requirements

### Requirement: Conversation turn termination on AccessDenied and PermissionRequired
When `ConversationSession` receives `ToolExecutionResult.AccessDenied` or `ToolExecutionResult.PermissionRequired` during the ReAct tool execution loop, it SHALL emit `ConversationEvent.ToolExecuted` containing the appropriate `ToolResult` (`ToolErrorCode.ACCESS_DENIED` or `ToolErrorCode.PERMISSION_DENIED`), record the failure as a `ConversationTurn.ToolExecutionResult`, and prompt the LLM with the structured reason and guidance under a response-only constraint (supporting both LiteRT regex and GBNF) so the LLM synthesizes the user-facing explanation in the user's active language before completing the turn. If an internal pre-lookup in `ConversationSession` returns `AccessDenied`, or if the LLM generation fails, it SHALL fall back to deterministic text derived from `decision.message` or default rationale.

#### Scenario: ReAct tool execution denied by policy
- **WHEN** `executeDetailed()` returns `ToolExecutionResult.AccessDenied`
- **THEN** `ConversationSession` emits `ConversationEvent.ToolExecuted` with `ToolErrorCode.ACCESS_DENIED`
- **AND** records `ConversationTurn.ToolExecutionResult`
- **AND** prompts the LLM with response-only constraint to generate the denial explanation in the user's language
- **AND** finishes the turn upon receiving the generated response

#### Scenario: ReAct tool execution requires permission
- **WHEN** `executeDetailed()` returns `ToolExecutionResult.PermissionRequired`
- **THEN** `ConversationSession` emits `ConversationEvent.ToolExecuted` with `ToolErrorCode.PERMISSION_DENIED`
- **AND** records `ConversationTurn.ToolExecutionResult`
- **AND** prompts the LLM with response-only constraint to generate the permission rationale in the user's language
- **AND** finishes the turn upon receiving the generated response

#### Scenario: Pre-lookup tool execution denied by policy
- **WHEN** an internal pre-lookup in `ConversationSession` returns `ToolExecutionResult.AccessDenied`
- **THEN** `ConversationSession` does not proceed to action confirmation or execution
- **AND** terminates the turn immediately with denial guidance
