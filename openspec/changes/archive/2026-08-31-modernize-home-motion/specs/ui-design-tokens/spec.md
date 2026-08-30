## MODIFIED Requirements

### Requirement: Centralized typography, shape, and spacing tokens
The application SHALL define a centralized token system (`Type.kt`, `Shapes.kt`, `LokiTokens.kt` in `core/ui/.../theme/`) providing typography, corner-shape, and spacing values, wired into `MaterialTheme` via `LokiTheme`. ALL composables — including `SetupScreen`, `PermissionsScreen`, `AgentPlaygroundScreen`, and `SettingsScreen` — SHALL consume these tokens (or `MaterialTheme.*` accessors) instead of declaring hardcoded `sp` font sizes, corner radii, or ad-hoc spacing constants. Icons SHALL be Material vector icons (`material-icons-extended`); unicode text glyphs SHALL NOT be used as UI icons.

#### Scenario: Screens use theme typography
- **WHEN** any screen renders text
- **THEN** the text style comes from `MaterialTheme.typography.*` (backed by `Type.kt`) rather than a hardcoded `fontSize = N.sp`

#### Scenario: Screens use theme shapes
- **WHEN** a surface or container defines corner rounding
- **THEN** it references `MaterialTheme.shapes.*` or a named constant from `Shapes.kt` rather than an inline `RoundedCornerShape(N.dp)`

#### Scenario: Vector icons replace glyphs
- **WHEN** any screen renders an icon (back arrows, send, mic, stop, hamburger, checkmarks, expand/collapse)
- **THEN** a Material vector icon is used; no unicode text glyphs appear as icons
