# app-shell — Delta

## ADDED Requirements

### Requirement: Settings hosts the conversation-language picker
The Settings screen SHALL include a "Conversation language" row (Auto plus a fixed list of common languages) that persists through the agent-config path and takes effect for new conversations; the Agent Playground config editor SHALL expose the same field.

#### Scenario: Language picker persists
- **WHEN** the user selects a language in Settings
- **THEN** the choice persists across app restarts via the agent config
