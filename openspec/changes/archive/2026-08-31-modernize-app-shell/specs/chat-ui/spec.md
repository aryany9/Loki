## MODIFIED Requirements

### Requirement: Full-screen Jetpack Compose Chat Activity for direct launcher opens
When the user opens Loki directly from the application launcher (i.e. launching `MainActivity`), the system SHALL display a full-screen Jetpack Compose chat interface instead of the voice interaction overlay. The chat interface SHALL host the navigation drawer shell (per the `app-shell` capability) and SHALL NOT present inline navigation icon buttons in its top bar — destinations are reached via the drawer.

#### Scenario: User launches app from home screen
- **WHEN** the user opens Loki via the app launcher icon
- **THEN** `MainActivity` renders a chat screen with conversation history, drawer access, text input field, and the morphing action button
- **AND** the screen is not a transient overlay
