## ADDED Requirements

### Requirement: Assistant-initiated navigation intents are honored when the app is already running
The app SHALL deliver an `openScreen` extra via `onNewIntent` and navigate to the requested
screen (PERMISSIONS, MODEL_LIBRARY, AGENT_PLAYGROUND, MEMORY, SETTINGS) when the assistant
launches `MainActivity` while the activity is already alive, instead of ignoring the intent.

#### Scenario: Permissions screen opens from the voice overlay
- **WHEN** the voice flow reports a permission denial and launches `MainActivity` with
  `openScreen=PERMISSIONS` while the app is already open
- **THEN** the app navigates to the Permissions screen
- **AND** the user is not left on the screen they were previously viewing

#### Scenario: Deep link ignored for unknown targets
- **WHEN** an `openScreen` extra contains a value that is not a known screen
- **THEN** no navigation occurs and the app remains on its current screen
