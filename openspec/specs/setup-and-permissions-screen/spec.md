### Requirement: First-run setup screen is shown on fresh install
On a fresh install, the app SHALL display a `SetupScreen` before `ChatScreen`. The setup screen SHALL explain why `RECORD_AUDIO` is required and SHALL request it via the Android runtime permission API.

#### Scenario: Fresh install shows setup screen
- **WHEN** the app is launched for the first time (no models installed, `isFirstRunComplete = false`)
- **THEN** `SetupScreen` is shown instead of `ChatScreen`

#### Scenario: Models cleared after first run still shows setup screen
- **WHEN** `isFirstRunComplete` is `true` but either `LITERT_LM` or `LITERT_ASR` is not `LOADED`
- **THEN** `SetupScreen` is shown instead of `ChatScreen`

#### Scenario: Returning user with all mandatory models ready skips setup
- **WHEN** `isFirstRunComplete` is `true` AND both `LITERT_LM` and `LITERT_ASR` are `LOADED`
- **THEN** `ChatScreen` is shown directly without displaying `SetupScreen`

#### Scenario: Setup completes after both runtimes are ready
- **WHEN** the user confirms "Get Started" and both runtimes are `LOADED`
- **THEN** `first_run_complete` is set to `true` in DataStore and the app navigates to `ChatScreen`

### Requirement: SetupScreen displays all tool permissions with status
`SetupScreen` (and the persistent `PermissionsScreen`) SHALL display a list of every permission required by registered tools, with each entry showing: permission name, a human-readable reason, the current status (Granted / Not Granted / Permanently Denied), and an action button.

#### Scenario: Requestable permission shows Grant button
- **WHEN** `CALL_PHONE` permission is `REQUESTABLE`
- **THEN** the entry shows a "Grant" button that triggers the Android permission dialog

#### Scenario: Permanently denied permission shows Settings button
- **WHEN** `READ_CONTACTS` permission is `PERMANENTLY_DENIED`
- **THEN** the entry shows an "Open Settings" button that navigates to the App Settings screen

#### Scenario: Granted permission shows check mark
- **WHEN** `RECORD_AUDIO` permission is `GRANTED`
- **THEN** the entry shows a green check mark and no action button

### Requirement: PermissionsScreen is accessible from the chat UI
A `PermissionsScreen` SHALL be accessible from the main `ChatScreen` (e.g., via a settings icon or navigation item) at any time after setup, not only on first run.

#### Scenario: User accesses permissions from chat
- **WHEN** the user taps the permissions/settings entry in `ChatScreen`
- **THEN** `PermissionsScreen` is displayed showing the current status of all tool permissions

### Requirement: CALL_PHONE and READ_CONTACTS are declared in AndroidManifest
`AndroidManifest.xml` SHALL declare `android.permission.CALL_PHONE` and `android.permission.READ_CONTACTS` as `<uses-permission>` entries.

#### Scenario: Missing permission declarations cause no crashes
- **WHEN** `CALL_PHONE` and `READ_CONTACTS` are declared in the manifest and requested at runtime
- **THEN** the Android permission dialog appears correctly and the ToolRegistry permission check reflects the grant result
