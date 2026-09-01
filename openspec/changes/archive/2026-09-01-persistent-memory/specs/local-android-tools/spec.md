# local-android-tools — Delta

## ADDED Requirements

### Requirement: Memory capture and history retrieval tools
The system SHALL implement two additional `LocalTool` implementations (registered set 17 total): `remember_fact(content)` — persists a durable user fact to the memory store, deduplicating identical trimmed text by refreshing its timestamp instead of duplicating; `search_chat_history(query)` — keyword-searches all stored conversations' user/assistant turns and returns up to 5 result snippets with conversation title and date. Neither tool SHALL require confirmation; both SHALL function offline.

#### Scenario: Model remembers a fact
- **WHEN** the user says "remember that my bike code is 4321" and the model invokes `remember_fact(content="Bike code is 4321")`
- **THEN** the fact is persisted with source `MODEL_TOOL`
- **AND** the model confirms to the user that it will remember

#### Scenario: Duplicate fact deduped
- **WHEN** `remember_fact` is invoked with text identical (trimmed) to an existing entry
- **THEN** no duplicate entry is created
- **AND** the existing entry's updated timestamp is refreshed

#### Scenario: New chat reaches prior history
- **WHEN** the user in a brand-new chat asks "what did I ask about the exam last week?" and the model invokes `search_chat_history(query="exam")`
- **THEN** matching turns from prior conversations are returned as snippets with conversation title and date
- **AND** the model can answer from those snippets

#### Scenario: Search finds nothing
- **WHEN** `search_chat_history` matches no stored turns
- **THEN** the tool returns an empty-results success
- **AND** the model tells the user it found nothing rather than inventing content
