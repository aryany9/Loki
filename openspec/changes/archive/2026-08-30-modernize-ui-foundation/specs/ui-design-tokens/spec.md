## ADDED Requirements

### Requirement: Centralized typography, shape, and spacing tokens
The application SHALL define a centralized token system (`Type.kt`, `Shapes.kt`, `LokiTokens.kt` in `core/ui/.../theme/`) providing typography, corner-shape, and spacing values, wired into `MaterialTheme` via `LokiTheme`. New or modified composables SHALL consume these tokens (or `MaterialTheme.*` accessors) instead of declaring hardcoded `sp` font sizes, corner radii, or ad-hoc spacing constants.

#### Scenario: Screens use theme typography
- **WHEN** a screen renders text
- **THEN** the text style comes from `MaterialTheme.typography.*` (backed by `Type.kt`) rather than a hardcoded `fontSize = N.sp`

#### Scenario: Screens use theme shapes
- **WHEN** a surface or container defines corner rounding
- **THEN** it references `MaterialTheme.shapes.*` or a named constant from `Shapes.kt` rather than an inline `RoundedCornerShape(N.dp)`

### Requirement: Material 3 Expressive available via current Compose BOM
The version catalog SHALL reference the latest stable Compose BOM, and all Compose UI modules SHALL resolve their Compose/material3 artifacts through it. The build SHALL compile with no unresolved Expressive-era Material 3 APIs.

#### Scenario: Version catalog upgrade
- **WHEN** the Compose BOM version in `gradle/libs.versions.toml` is updated to the latest stable and the project builds
- **THEN** all modules using Compose resolve against the new BOM and compile successfully
