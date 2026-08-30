# modernize-ui-foundation

## Why

The Loki UI is built on Compose BOM 2024.08.00 with a static hand-tuned color scheme and hardcoded font sizes/corner radii across screens. The broader goal is a modern Gemini-style experience; before any visual redesign, the design foundation (Dynamic Color, Material 3 Expressive, a token system, edge-to-edge) must be in place so subsequent UI phases inherit it instead of being restyled twice.

## What Changes

- Upgrade Compose BOM from `2024.08.00` to the latest stable (unlocks Material 3 Expressive APIs) in `gradle/libs.versions.toml`.
- Rewrite `LokiTheme.kt`: on Android 12+ (API 31+), use wallpaper-based Dynamic Color (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) — Gemini-style, always-on, no user toggle. Devices on API 29/30 fall back to the existing `LokiDarkColorScheme` / `LokiLightColorScheme`.
- Introduce a design token system (`Type.kt`, `Shapes.kt` / `LokiTokens`) for typography, corner shapes, and spacing, so screens stop hardcoding `fontSize = 15.sp` and corner radii.
- Enable edge-to-edge rendering in `MainActivity` (`enableEdgeToEdge()`) with correct `WindowInsets` handling in Scaffolds — required on `targetSdk = 35`.
- Out of scope: chat surface redesign (bubbles → full-width messages), markdown rendering, composer redesign, home/greeting state, motion/transitions, and visual redesign of Setup/Permissions/Model Library/Agent Playground/Voice Overlay screens (these only receive the new theme automatically).

## Capabilities

### New Capabilities
- `ui-design-tokens`: Centralized typography, shape, and spacing tokens for all Loki composables; rule that screens consume tokens instead of hardcoded dp/sp values.

### Modified Capabilities
- `loki-theme`: Adds a requirement for Dynamic Color (wallpaper-based scheme on Android 12+ with existing hand-tuned schemes as API 29/30 fallback, no user toggle); existing persistence and no-hardcoded-colors requirements are unchanged.

## Impact

- **Code**: `core/ui/src/main/java/dev/loki/android/core/ui/theme/LokiTheme.kt` (rewrite), new token files in `core/ui/.../theme/`, `app/src/main/java/dev/loki/android/ui/MainActivity.kt` (edge-to-edge), version catalog `gradle/libs.versions.toml`.
- **Dependencies**: Compose BOM bump (transitive: ui, material3). No new libraries.
- **Specs**: `openspec/specs/loki-theme/spec.md` (delta), new `ui-design-tokens` spec.
- **Devices**: Visual appearance changes on Android 12+ (wallpaper-derived palette). Behavior on API 29/30 unchanged.
