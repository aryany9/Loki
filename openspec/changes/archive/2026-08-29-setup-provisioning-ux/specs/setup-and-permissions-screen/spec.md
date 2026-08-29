## MODIFIED Requirements

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
