### Requirement: App supports light, dark, and system-default themes
The application SHALL provide three theme modes: `DARK`, `LIGHT`, and `SYSTEM`. `SYSTEM` follows the Android system dark mode setting.

#### Scenario: Dark theme applied
- **WHEN** theme mode is `DARK`
- **THEN** all screens use the `LokiDarkColorScheme` Material 3 color scheme

#### Scenario: Light theme applied
- **WHEN** theme mode is `LIGHT`
- **THEN** all screens use the `LokiLightColorScheme` Material 3 color scheme

#### Scenario: System theme follows device setting
- **WHEN** theme mode is `SYSTEM` and the device is in dark mode
- **THEN** `LokiDarkColorScheme` is applied; when the device is in light mode, `LokiLightColorScheme` is applied

### Requirement: Theme selection persists across app restarts
The selected `ThemeMode` SHALL be persisted via `DataStore<Preferences>` and restored when the app is re-launched.

#### Scenario: Theme survives restart
- **WHEN** user selects `LIGHT` mode and then force-closes and reopens the app
- **THEN** the app launches in `LIGHT` mode without any flash or system-default override

### Requirement: No hardcoded colors in UI composables
All composable color values in `ChatScreen`, `VoiceSessionOverlay`, `SetupScreen`, and `PermissionsScreen` SHALL reference `MaterialTheme.colorScheme.*` tokens. Hardcoded hex `Color()` literals SHALL NOT appear in composable layout code.

#### Scenario: Chat screen respects theme
- **WHEN** theme changes from dark to light at runtime
- **THEN** `ChatScreen` background, message bubbles, and input bar immediately reflect the new color scheme without recomposition triggers from hardcoded values

### Requirement: XML theme parent is Material 3
The `themes.xml` `Theme.Loki` style SHALL have `Theme.Material3` (or `Theme.MaterialComponents.DayNight.NoActionBar`) as its parent so the Android system chrome (status bar, navigation bar) correctly follows the selected mode.

#### Scenario: Status bar follows dark theme
- **WHEN** dark mode is active
- **THEN** the status bar and navigation bar appear with dark backgrounds and light icons
