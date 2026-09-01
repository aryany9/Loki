## Purpose
Application shell: navigation drawer with conversation recents, simplified chat top bar, model info popover, and a Settings screen with theme selection.

## Requirements

### Requirement: Navigation drawer hosts recents and destinations
The chat surface SHALL be wrapped in a Material 3 navigation drawer containing: an app header, a "New chat" action, a recents list of stored conversations (most recently updated first, capped display at 20) where tapping loads that conversation, a delete affordance per recent, and navigation entries for Model Library, Agent Playground, Permissions, and Settings. The drawer state SHALL close on selection, and the system back gesture SHALL close the drawer before exiting the app. "New chat" SHALL reset the chat to the empty home state and SHALL NOT create a stored conversation until the first message is sent.

#### Scenario: User opens a recent conversation
- **WHEN** the user taps a conversation in the drawer recents list
- **THEN** that conversation's history loads into the chat surface and the drawer closes

#### Scenario: User starts a new chat from the drawer
- **WHEN** the user taps "New chat" in the drawer
- **THEN** the chat surface resets to the empty home state and the drawer closes, with no stored conversation created until a first message is sent

#### Scenario: Drawer navigates to settings
- **WHEN** the user taps "Settings" in the drawer
- **THEN** the Settings screen is displayed and the drawer closes

#### Scenario: Back gesture closes drawer first
- **WHEN** the drawer is open and the user performs the back gesture
- **THEN** the drawer closes instead of the app exiting

---

### Requirement: System back navigation walks the destination stack
Pressing the system back button on a non-root screen (Settings, Model Library, Agent Playground, Permissions) SHALL return to the previously displayed destination, ultimately to the chat home. From the chat home (the root), the system back SHALL finish the activity as expected. No non-root screen SHALL directly exit the app on a single back press.

#### Scenario: Back returns to chat from settings
- **WHEN** the user is on the Settings screen and presses the system back button once
- **THEN** the app returns to the chat home instead of exiting

#### Scenario: Back walks nested navigation
- **WHEN** the user navigates chat → Agent Playground → Model Library and presses back twice
- **THEN** the app first returns to Agent Playground, then to chat

#### Scenario: Back from chat exits
- **WHEN** the user is on the chat home and presses the system back button
- **THEN** the activity finishes normally

---

### Requirement: Simplified chat top bar
The chat TopAppBar SHALL contain only the drawer toggle (hamburger), the screen title, and the model status badge — previously inline navigation icon buttons SHALL be removed in favor of the drawer entries.

#### Scenario: Top bar decluttered
- **WHEN** the chat screen is displayed
- **THEN** no inline icon buttons for Model Library, Agent Playground, Permissions, or new chat appear in the top bar; these destinations are reachable from the drawer

### Requirement: Model status badge opens an info popover
The model status badge SHALL be tappable and SHALL display an anchored info card showing the current model's display name, its state (with retry affordance on error), and a "Manage models" action that navigates to Model Library.

#### Scenario: Inspecting model state
- **WHEN** the user taps the model status badge
- **THEN** a popover shows the current model name and state, with a retry action when in the error state

### Requirement: Settings screen with theme selection
A Settings screen SHALL provide: a theme mode selector (SYSTEM, LIGHT, DARK) that persists via `ThemeRepository` and applies immediately; the current model's name/state (read-only, with retry on error); links to Model Library, Agent Playground, and Permissions; and the app version. Changes SHALL take effect without app restart and survive relaunch.

#### Scenario: Theme change applies immediately
- **WHEN** the user selects LIGHT while the app is in dark mode
- **THEN** the entire UI switches to the light color scheme immediately, without restart

#### Scenario: Theme persists
- **WHEN** the user selects DARK, force-closes, and reopens the app
- **THEN** the app launches in dark mode

#### Scenario: Settings reachable from drawer
- **WHEN** the user opens the navigation drawer and taps Settings
- **THEN** the Settings screen is displayed with a back affordance returning to chat

### Requirement: Settings hosts the memory management section
The Settings screen SHALL include a "What Loki remembers" section listing all memory entries with an add field, per-entry edit/delete, and clear-all with confirmation, styled with theme tokens and stating that memories apply to new chats.

#### Scenario: Memory section reflects store state
- **WHEN** memories are added, edited, or deleted in the section
- **THEN** the list updates immediately from the store
- **AND** an empty store shows a "Nothing remembered yet" empty state

---

### Requirement: Settings hosts the conversation-language picker
The Settings screen SHALL include a "Conversation language" row (Auto plus a fixed list of common languages) that persists through the agent-config path and takes effect for new conversations; the Agent Playground config editor SHALL expose the same field.

#### Scenario: Language picker persists
- **WHEN** the user selects a language in Settings
- **THEN** the choice persists across app restarts via the agent config

