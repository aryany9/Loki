## Purpose

Define requirements for the centralized design token system, typography scale, corner shapes, and vector icons in Loki.

## Requirements

### Requirement: Centralized typography, shape, and spacing tokens
The application SHALL define a centralized token system (`Type.kt`, `Shapes.kt`, `LokiTokens.kt`, `LokiTheme.kt`, `ThemeRepository.kt`) in a dedicated low-level `core/theme` module (package `dev.loki.android.core.theme`), providing typography, corner-shape, spacing, and theming values, wired into `MaterialTheme` via `LokiTheme`. Both `core/assistant` and `core/ui` SHALL depend on `core:theme`; the theme SHALL NOT live in a module that creates a dependency cycle. All composables — including `SetupScreen`, `PermissionsScreen`, `AgentPlaygroundScreen`, `SettingsScreen`, and the voice overlay — SHALL consume these tokens (or `MaterialTheme.*` accessors) instead of declaring hardcoded `sp` font sizes, corner radii, or ad-hoc spacing constants. Icons SHALL be Material vector icons (`material-icons-extended`); unicode text glyphs SHALL NOT be used as UI icons.

#### Scenario: Screens use theme typography
- **WHEN** any screen renders text
- **THEN** the text style comes from `MaterialTheme.typography.*` (backed by `Type.kt`) rather than a hardcoded `fontSize = N.sp`

#### Scenario: Screens use theme shapes
- **WHEN** a surface or container defines corner rounding
- **THEN** it references `MaterialTheme.shapes.*` or a named constant from `Shapes.kt` rather than an inline `RoundedCornerShape(N.dp)`

#### Scenario: Vector icons replace glyphs
- **WHEN** any screen renders an icon (back arrows, send, mic, stop, hamburger, checkmarks, expand/collapse)
- **THEN** a Material vector icon is used; no unicode text glyphs appear as icons

#### Scenario: Theme module has no dependency cycles
- **WHEN** the module dependency graph is inspected
- **THEN** `core/theme` is a leaf module (depends only on core-ktx/Compose/DataStore), and both `core/assistant` and `core/ui` resolve `LokiTheme` from it

### Requirement: Material 3 Expressive available via current Compose BOM
The version catalog SHALL reference the latest stable Compose BOM, and all Compose UI modules SHALL resolve their Compose/material3 artifacts through it. The build SHALL compile with no unresolved Expressive-era Material 3 APIs.

#### Scenario: Version catalog upgrade
- **WHEN** the Compose BOM version in `gradle/libs.versions.toml` is updated to the latest stable and the project builds
- **THEN** all modules using Compose resolve against the new BOM and compile successfully
