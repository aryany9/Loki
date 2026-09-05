## MODIFIED Requirements

### Requirement: `GrammarBuilder` generates GBNF from `ToolRegistry` at runtime
The system SHALL provide a `GrammarBuilder` component that dynamically generates a GBNF grammar string from the set of tools currently visible per `ToolRegistry` scoping (permission, environment, and capability scope). The grammar SHALL constrain `LlmEngine` output to syntactically valid tool calls or a final natural-language response token.

#### Scenario: Grammar generated from registered tools
- **WHEN** `GrammarBuilder.buildFrom(toolRegistry)` is called with tools `[call_contact, get_battery_status, open_app]`
- **THEN** the returned GBNF grammar encodes exactly those tool names as valid options
- **AND** the grammar encodes the parameter types (string, integer, enum) for each tool's schema

#### Scenario: Grammar reflects the scoped visible set
- **WHEN** the `calling` capability is active and the scoped set is `general` + `calling` tools
- **THEN** the generated grammar encodes only those tool names
- **AND** out-of-scope tool names are not producible during generation

### Requirement: Grammar regenerated when tool set changes
`GrammarBuilder` SHALL regenerate the grammar when the visible tool set changes — whether from registry membership changes, permission/environment availability, or capability scope activation/deactivation — so that the LLM's output constraints always reflect the currently visible tool set.

#### Scenario: Grammar updated after tool registration
- **WHEN** a new tool is registered in `ToolRegistry` during runtime
- **THEN** the next inference call uses a grammar that includes the new tool's name and parameters

#### Scenario: Grammar updated on capability activation
- **WHEN** a capability activates or deactivates and the scoped visible set changes
- **THEN** the next inference call uses a grammar matching the new scoped set
