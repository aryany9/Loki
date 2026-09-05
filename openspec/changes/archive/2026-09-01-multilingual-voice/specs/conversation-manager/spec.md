# conversation-manager — Delta

## ADDED Requirements

### Requirement: System prompt carries the response-language directive
`buildSystemPrompt` SHALL append exactly one language directive derived from `AgentConfig.conversationLanguage`: `"auto"` instructs the model to respond in the same language the user writes or speaks; an explicit tag instructs it to always respond in that language (display name). The directive SHALL be positioned so it cannot displace safety or tool-signature prompt content.

#### Scenario: Mirrored response language
- **WHEN** `conversationLanguage = "auto"` and the user writes in Spanish
- **THEN** the system prompt instructs responding in the user's language
- **AND** the assistant responds in Spanish (within the loaded model's ability)

#### Scenario: Locked response language
- **WHEN** `conversationLanguage = "fr"`
- **THEN** the system prompt instructs always responding in French regardless of input language
