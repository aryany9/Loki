## 1. Dependency upgrade

- [x] 1.1 Bump `composeBom` in `gradle/libs.versions.toml` to the latest stable release
- [x] 1.2 Build all modules (`./gradlew :app:assembleDebug`) and fix any deprecation/compile issues from the BOM bump without behavior changes

## 2. Theme foundation

- [x] 2.1 Add `theme/Type.kt` with a Material 3 `Typography` definition (Expressive-scale weights/sizes)
- [x] 2.2 Add `theme/Shapes.kt` with `Shapes` and semantic corner constants (e.g. messageBubble, inputBar)
- [x] 2.3 Add `theme/LokiTokens.kt` with a spacing scale (4/8/12/16/24/32.dp)
- [x] 2.4 Rewrite `LokiTheme.kt` scheme selection: on API 31+ use `dynamicDarkColorScheme`/`dynamicLightColorScheme(context)`, otherwise fall back to the existing static schemes; keep the `LokiTheme(themeMode, content)` signature unchanged
- [x] 2.5 Wire `typography` and `shapes` into the `MaterialTheme` call inside `LokiTheme`

## 3. Edge-to-edge

- [x] 3.1 Call `enableEdgeToEdge()` in `MainActivity.onCreate`
- [x] 3.2 Verify `WindowInsets` handling in `ChatScreen`, `SetupScreen`, `PermissionsScreen`, `ModelLibraryScreen`, and `AgentPlaygroundScreen` Scaffolds (status/nav bars consumed, `imePadding` on input areas); fix any obscured content
- [x] 3.3 Verify `SessionOverlay` still renders correctly over an edge-to-edge activity; compensate insets if needed

## 4. Token adoption in shared components

- [x] 4.1 Replace hardcoded `fontSize`/corner radii in `ChatMessage.kt` / `ChatBubble` with theme tokens (minimal change, no layout redesign)
- [x] 4.2 Grep for remaining hardcoded `Color(0x...)` literals in composables and confirm all screens reference `MaterialTheme.colorScheme.*` only

## 5. Validation

- [x] 5.1 Run unit tests (`./gradlew test`) and instrumented `ModelLibraryScreenTest` where a device/emulator is available
- [x] 5.2 Manual check on Android 12+ device/emulator: wallpaper-derived colors appear; DARK/LIGHT/SYSTEM modes all switch correctly
- [x] 5.3 Manual check on API 29/30 emulator: existing Loki light/dark palette unchanged, theme persistence survives restart
- [x] 5.4 Manual check: edge-to-edge rendering, keyboard interactions, and voice overlay all behave without obscured or clipped content
- [x] 5.5 Run `openspec validate modernize-ui-foundation` and confirm all tasks complete
