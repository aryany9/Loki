## ADDED Requirements

### Requirement: `GrammarBuilder` generates GBNF from `ToolRegistry` at runtime
The system SHALL provide a `GrammarBuilder` component that dynamically generates a GBNF grammar string from the set of tools currently registered in `ToolRegistry`. The grammar SHALL constrain `LlmEngine` output to syntactically valid tool calls or a final natural-language response token.

#### Scenario: Grammar generated from registered tools
- **WHEN** `GrammarBuilder.buildFrom(toolRegistry)` is called with tools `[call_contact, get_battery_status, open_app]`
- **THEN** the returned GBNF grammar encodes exactly those tool names as valid options
- **AND** the grammar encodes the parameter types (string, integer, enum) for each tool's schema

---

### Requirement: Grammar regenerated when tool set changes
`GrammarBuilder` SHALL regenerate the grammar when tools are added to or removed from `ToolRegistry`, so that the LLM's output constraints always reflect the current tool set.

#### Scenario: Grammar updated after tool registration
- **WHEN** a new tool is registered in `ToolRegistry` during runtime
- **THEN** the next inference call uses a grammar that includes the new tool's name and parameters

---

### Requirement: Grammar constrains tool names to registered names only
The generated grammar SHALL enumerate tool names as a closed set of string literals. The LLM SHALL be unable to produce an unknown tool name when grammar constraints are active.

#### Scenario: Unknown tool name not producible
- **WHEN** `LlmEngine.generate()` is called with the GBNF grammar from `GrammarBuilder`
- **THEN** the output contains only tool names present in `ToolRegistry`
- **AND** hallucinated tool names are mathematically impossible

---

### Requirement: Grammar supports a final response alternative
The generated grammar SHALL include a production rule for a final natural-language response (i.e., a plain text string), so that the LLM can produce conversational responses when no tool is needed.

#### Scenario: LLM produces final response when no tool needed
- **WHEN** the user asks "What time is it?" and the grammar includes both tool-call and response alternatives
- **THEN** the LLM may produce either a `get_current_time` tool call or a response string
- **AND** both outputs are syntactically valid per the grammar

---

### Requirement: `ToolRegistry` remains the validation authority
Grammar-constrained output guarantees syntactic validity only. `ToolRegistry` SHALL still perform semantic argument validation on all tool calls regardless of whether grammar constraints were active during generation.

#### Scenario: Valid grammar output still validated semantically
- **WHEN** the LLM produces `{"tool": "call_contact", "arguments": {"name": ""}}` (syntactically valid, semantically empty)
- **THEN** `ToolRegistry.validate()` rejects the call with a semantic validation error
- **AND** execution does not proceed
