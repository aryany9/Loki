# capability-driven-tool-policy Specification

## Purpose
TBD - created by archiving change modality-scoped-conversation-architecture. Update Purpose after archive.
## Requirements
### Requirement: Decoupled ToolPolicy Interface
`core:tools` SHALL expose a pure functional interface `fun interface ToolPolicy { fun isAllowed(tool: Tool): Boolean }` with a companion default `ToolPolicy.ALL = ToolPolicy { true }`. `ToolRegistry.getAvailableTools` SHALL accept an optional `policy: ToolPolicy = ToolPolicy.ALL` parameter without introducing dependencies on conversation or domain model packages.

#### Scenario: Default policy allows all registered tools
- **WHEN** `ToolRegistry.getAvailableTools` is invoked without specifying a policy
- **THEN** all tools passing permission and task state checks are returned
- **AND** existing callers compile and execute without modification

#### Scenario: Custom policy filters tools
- **WHEN** a custom `ToolPolicy` is supplied that returns false for a specific tool
- **THEN** that tool is excluded from the returned available tools list

### Requirement: Intersection-Based Modality Tool Governance
`core:conversation` SHALL compute effective turn tools by taking the intersection of registered tools, active model capabilities, modality tool policy (`ChatToolPolicy` or `VoiceToolPolicy`), and dynamic task gates: `Effective = Registered ∩ ModelCapabilities ∩ ModePolicy ∩ TaskGates`. Only tools in the effective set SHALL have schemas injected into the prompt, and executions targeting excluded tools SHALL be rejected.

#### Scenario: Chat mode policy excludes ask_user
- **WHEN** tools are resolved for a session with `ConversationMode.CHAT`
- **THEN** `ask_user` is excluded by `ChatToolPolicy` regardless of registration
- **AND** its schema is not exposed to the model

#### Scenario: Voice mode policy permits ask_user
- **WHEN** tools are resolved for a session with `ConversationMode.VOICE`
- **THEN** `ask_user` is permitted by `VoiceToolPolicy` and available for turn state signaling

#### Scenario: Disallowed tool execution rejected
- **WHEN** a model attempts to invoke a tool excluded by the active modality policy
- **THEN** the session rejects execution without running the underlying tool action
- **AND** the session emits a `ToolResult.error(...)` coaching the model that the tool is unavailable in this mode
- **AND** the rejection is fed back to the model as a tool result (NOT a session-level `ConversationEvent.Error`), allowing the model to recover conversationally

