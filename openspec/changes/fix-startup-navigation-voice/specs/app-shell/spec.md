## MODIFIED REQUIREMENTS

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

## ADDED REQUIREMENTS

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