# modernize-ui-foundation — Design

## Context

Loki's UI is Jetpack Compose + Material 3 (BOM `2024.08.00`) across `core/ui` and `app`. Theming is a single `LokiTheme.kt` with two static hand-tuned `lightColorScheme`/`darkColorScheme` definitions (indigo/slate palette), persisted via `ThemeRepository` (`ThemeMode`: DARK/LIGHT/SYSTEM in DataStore). Screens hardcode `fontSize = 15.sp`, `RoundedCornerShape(16.dp)`, etc. `minSdk = 29`, `compileSdk`/`targetSdk = 35`. `MainActivity` does not currently render edge-to-edge. Kotlin 2.2.21 with the Compose compiler Gradle plugin, so a BOM bump requires no compiler changes.

## Goals / Non-Goals

**Goals:**
- Latest stable Compose BOM with Material 3 Expressive APIs available.
- Dynamic Color (wallpaper-based) on Android 12+, Gemini-style, always-on; no user-facing toggle.
- Existing schemes preserved verbatim as fallback for API 29/30.
- Centralized type/shape/spacing tokens; screens consume tokens.
- Edge-to-edge rendering with correct inset handling on all screens.

**Non-Goals:**
- Redesigning any screen layout (chat bubbles, composer, home state, motion) — later phases.
- Markdown rendering or any new UI dependency beyond the BOM bump.
- Changing `ThemeRepository`, `ThemeMode` persistence, or the settings UI.
- Migration of `loki-theme`'s persistence or no-hardcoded-colors requirements (unchanged).

## Decisions

### D1: Dynamic Color strategy — platform API only, no library
Use `androidx.compose.material3.dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` guarded by `Build.VERSION.SDK_INT >= 31` (uses `Settings.Secure.THEME_CUSTOMIZATION_SOURCE` / Monet under the hood). No third-party palette library.
*Why over alternatives:* Gemini does exactly this; third-party dynamic-theming libs add a dependency for something the platform provides. Rejected: user toggle for brand colors (user decision: brand identity not a priority).

### D2: `LokiTheme` keeps its existing signature
`LokiTheme(themeMode: ThemeMode, content)` is unchanged; only the scheme-selection logic inside changes. Callers (MainActivity and previews) need no edits for theming.

### D3: Token system — plain Kotlin objects, not a library
Add `theme/Type.kt` (Material 3 `Typography` with Expressive-scale weights/sizes), `theme/Shapes.kt` (`Shapes` plus semantic corner constants, e.g. `messageBubble = 20.dp`), and `theme/LokiTokens.kt` (spacing scale: 4/8/12/16/24/32.dp) passed into `MaterialTheme(typography = …, shapes = …)`.
*Why not a token library / design-system module:* overkill at this size; plain objects are grep-able and dependency-free.

### D4: Edge-to-edge in `MainActivity` only
Call `enableEdgeToEdge()` in `onCreate`; verify each Scaffold's insets handling (`imePadding`, `statusBarsPadding` via Scaffold defaults). No per-screen manual padding unless a screen breaks.

### D5: BOM bump alone, no other dependency moves
Bump `composeBom` in `libs.versions.toml`. `agp`/`kotlin` already compatible. Material3 Expressive APIs come transitively; nothing else changes.

## Risks / Trade-offs

- [Dynamic Color shifts Loki's visual identity on Android 12+] → Accepted per user decision; API 29/30 keeps the branded palette. If regretted later, an opt-out toggle is a small `LokiTheme` + settings change.
- [BOM bump may surface deprecations] → Build + existing instrumented test (`ModelLibraryScreenTest`) validate; fix deprecation warnings in passing, no behavior rewrites.
- [Edge-to-edge can break overlays (SessionOverlay draws over activity)] → Manually test voice overlay and keyboard scenarios on the new baseline; compensate insets where needed.
- [Token adoption is partial (screens keep hardcoded values this phase)] → Spec mandates tokens for *new/modified* code; a full sweep of existing screens belongs to later UI phases.

## Migration Plan

Single PR: version bump → theme rewrite → tokens → edge-to-edge, buildable and testable at each step. Rollback = revert the PR; no data/schema changes, DataStore untouched.

## Open Questions

None — Dynamic Color direction, no-toggle, and scope were confirmed with the user.
