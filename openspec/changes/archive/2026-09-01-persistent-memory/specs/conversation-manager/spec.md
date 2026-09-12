# conversation-manager — Delta

## ADDED Requirements

### Requirement: ConversationStore exposes keyword search over turns
`ConversationStore` SHALL provide `searchTurns(query, limit)`: a case-insensitive substring search across persisted user and assistant turn texts, skipping corrupt conversation files without failing, returning matches with conversation id, title, matched snippet, and timestamp.

#### Scenario: Corrupt file skipped during search
- **WHEN** one conversation file is corrupt and others contain matches
- **THEN** matches from the valid files are returned
- **AND** the corrupt file is logged and skipped
