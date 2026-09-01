# app-shell — Delta

## ADDED Requirements

### Requirement: Settings hosts the memory management section
The Settings screen SHALL include a "What Loki remembers" section listing all memory entries with an add field, per-entry edit/delete, and clear-all with confirmation, styled with theme tokens and stating that memories apply to new chats.

#### Scenario: Memory section reflects store state
- **WHEN** memories are added, edited, or deleted in the section
- **THEN** the list updates immediately from the store
- **AND** an empty store shows a "Nothing remembered yet" empty state
