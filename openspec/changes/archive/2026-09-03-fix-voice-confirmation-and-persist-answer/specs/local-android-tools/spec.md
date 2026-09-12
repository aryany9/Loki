## ADDED Requirements

### Requirement: Ambiguous contact names are disambiguated before calling
When a requested contact name matches multiple contacts, the model SHALL present the
matching contacts BY NAME ONLY (without speaking or displaying phone numbers; numbers are
reserved for the actual `call_contact` invocation) and ask the user which one to call
before invoking `call_contact` for a single selection. If more than 3 contacts match, the
model SHALL group or summarize the list to keep the spoken response short. A unique single
match SHALL keep the existing direct-confirm flow.

#### Scenario: Multiple matches are listed by name only
- **WHEN** the user says "Call Mom" and `lookup_contact` finds multiple matching contacts
- **THEN** the model lists the matches by name without reading phone numbers aloud
- **AND** the model asks which contact to call before invoking `call_contact`

#### Scenario: Large match sets are summarized
- **WHEN** `lookup_contact` finds more than 3 matching contacts
- **THEN** the model groups or summarizes the list so the spoken response stays short

#### Scenario: Unique match keeps the direct-confirm flow
- **WHEN** `lookup_contact` finds exactly one matching contact
- **THEN** the model proceeds to `call_contact` with that contact
- **AND** the normal confirmation gate applies before the call is placed

## MODIFIED Requirements

### Requirement: Tools return structured `ToolResult`
Every tool implementation SHALL return a `ToolResult` with structured JSON data. No tool
SHALL generate natural-language response text.

#### Scenario: lookup_contact returns structured matches
- **WHEN** `lookup_contact(query="Mom")` finds multiple contacts
- **THEN** the `ToolResult.data` contains all unique matching contacts, each with a name
  and phone number (deduplicated, capped at 10)
- **AND** no human-readable sentence is included in the result
- **AND** the result is not truncated to the first match
