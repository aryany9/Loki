## 1. Dependency

- [x] 1.1 Add `androidx.compose.material:material-icons-extended` (via BOM) to catalog + `core-ui` build file; verify debug/release build

## 2. Home greeting state

- [x] 2.1 Remove both seeded greeting messages from `ChatViewModel` (init list + `newConversation`)
- [x] 2.2 Add home composable in `ChatScreen`: greeting ("Hi there ✨", display-scale typography) + 4 static suggestion chips, rendered when `messages.isEmpty()`; chips call `sendMessage` with haptic tick
- [x] 2.3 Unit test: new conversation yields empty messages (no seeded greeting); selectConversation with turns renders them (no home state)

## 3. Vector icon sweep

- [x] 3.1 Replace glyphs in `ChatScreen.kt` (hamburger ☰, ➕, composer ▶/■/🎤, tool card ✓/✕/▲/▼)
- [x] 3.2 Replace glyphs in `ModelLibraryScreen.kt`, `SettingsScreen.kt`, `PermissionsScreen.kt`, `AgentPlaygroundScreen.kt` (← back, ✓/✗, ▶/▼ advanced toggle)
- [x] 3.3 Grep confirms zero remaining unicode glyph icons in `core/ui`

## 4. Motion + haptics

- [x] 4.1 Wrap MainActivity screen `when` content in `AnimatedContent` (fade+slide 200–250ms); verify back-direction feels natural
- [x] 4.2 Add light haptics on send, stop-generation (composer morphing button)

## 5. Token sweep

- [x] 5.1 Replace hardcoded `fontSize = N.sp` in SetupScreen (4), PermissionsScreen (2), AgentPlaygroundScreen (3) with `MaterialTheme.typography.*`
- [x] 5.2 Sweep remaining inline `RoundedCornerShape(N.dp)` in those screens to `LokiCornerTokens`
- [x] 5.3 Grep confirms zero hardcoded `fontSize = [0-9]` in `core/ui` composables

## 6. Validation

- [x] 6.1 `./gradlew test :app:assembleDebug` passes; check release APK size delta from icons-extended (minify on)
- [x] 6.2 Manual: new chat → home state with chips; chip tap sends message; restored conversation shows no home state; icons render correctly in dark/light; screen transitions smooth; haptics fire
- [x] 6.3 Run `openspec validate modernize-home-motion` and confirm all tasks complete
