## ADDED Requirements

### Requirement: Dynamic Color on Android 12+
On devices running Android 12 (API 31) or higher, `LokiTheme` SHALL derive its color scheme from the user's wallpaper using `dynamicDarkColorScheme` / `dynamicLightColorScheme`. Dynamic Color SHALL be always-on (no user-facing toggle) and SHALL respect the persisted `ThemeMode` for light/dark selection. On devices below API 31, `LokiTheme` SHALL fall back to the existing `LokiDarkColorScheme` / `LokiLightColorScheme` unchanged.

#### Scenario: Dynamic light scheme on Android 12+
- **WHEN** the app runs on an Android 12+ device with `ThemeMode` `LIGHT` or `SYSTEM` (device in light mode)
- **THEN** the applied color scheme is generated from the wallpaper palette via `dynamicLightColorScheme`, not the hand-tuned fallback

#### Scenario: Dynamic dark scheme on Android 12+
- **WHEN** the app runs on an Android 12+ device with `ThemeMode` `DARK` or `SYSTEM` (device in dark mode)
- **THEN** the applied color scheme is generated from the wallpaper palette via `dynamicDarkColorScheme`

#### Scenario: Fallback on Android 10–11
- **WHEN** the app runs on a device with API level 29 or 30
- **THEN** the existing hand-tuned `LokiLightColorScheme` / `LokiDarkColorScheme` are applied exactly as before

## MODIFIED Requirements

### Requirement: XML theme parent is Material 3
The `themes.xml` `Theme.Loki` style SHALL have `Theme.Material3` (or `Theme.MaterialComponents.DayNight.NoActionBar`) as its parent so the Android system chrome (status bar, navigation bar) correctly follows the selected mode. `MainActivity` SHALL call `enableEdgeToEdge()` and all screens SHALL handle `WindowInsets` (status bar, navigation bar, IME) so content is not obscured when rendering edge-to-edge on `targetSdk = 35`.

#### Scenario: Status bar follows dark theme
- **WHEN** dark mode is active
- **THEN** the status bar and navigation bar appear with dark backgrounds and light icons

#### Scenario: Edge-to-edge rendering without obscured content
- **WHEN** the app renders on a device enforcing edge-to-edge (Android 15 / `targetSdk = 35`) and the keyboard is opened on the chat input
- **THEN** the status bar, navigation bar, and IME do not permanently obscure any interactive content (system bar insets are consumed by Scaffolds and the IME is handled via `imePadding`)
