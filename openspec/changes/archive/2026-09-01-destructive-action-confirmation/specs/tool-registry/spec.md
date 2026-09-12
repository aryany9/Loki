# tool-registry — Delta

## ADDED Requirements

### Requirement: Confirmation metadata is exposed by the registry
The `Tool` interface SHALL expose `requiresConfirmation: Boolean` (default `false`) and `describeAction(arguments): String`. `ToolRegistry` SHALL surface both for a given tool name so the conversation layer can gate execution without importing concrete tool classes.

#### Scenario: Registry reports confirmation requirement
- **WHEN** the conversation layer queries a registered gated tool
- **THEN** the registry reports `requiresConfirmation = true` and the tool-provided repeat-back for the parsed arguments

#### Scenario: Existing tools remain source-compatible
- **WHEN** the interface gains the new members with defaults
- **THEN** all previously registered tools compile and behave unchanged
