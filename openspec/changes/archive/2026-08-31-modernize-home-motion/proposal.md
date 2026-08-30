# modernize-home-motion

## Why

The final phase of the UI modernization roadmap: the chat screen still opens onto a fake "greeting" assistant message, every screen uses unicode text glyphs (▶ ■ 🎤 ☰ ← ✓ ▼) as icons, screen switches are abrupt with no transitions, and Setup/Permissions/AgentPlayground still contain hardcoded font sizes. These are the remaining gaps to a polished, Gemini/ChatGPT-feeling app.

## What Changes

- **Home/empty state**: when the active conversation has no turns, the chat surface shows a Gemini-style greeting (dynamic "Hi there ✨"-style text, large expressive typography) plus tappable suggestion chips; chips populate-and-send the composer. The seeded fake greeting assistant message is removed.
- **Vector icons**: replace all unicode text glyphs across screens with Material vector icons via `material-icons-extended` (new dependency; R8 strips unused icons in release).
- **Screen transitions**: crossfade/slide transitions between `AppScreen` destinations in MainActivity; subtle motion on drawer recents items.
- **Haptics**: light haptic feedback on send, stop-generation, and chip taps.
- **Token sweep**: replace remaining hardcoded `fontSize = N.sp` / corner radii in SetupScreen, PermissionsScreen, AgentPlaygroundScreen with theme typography/shape tokens.
- Out of scope: Hilt-ViewModel migration, conversation file-retention cap, in-drawer rename, model-switcher dropdown.

## Capabilities

### New Capabilities
- `home-greeting`: Empty-conversation home state with personalized greeting and suggestion chips.

### Modified Capabilities
- `ui-design-tokens`: Extends the token-consumption rule to now cover ALL screens (Setup, Permissions, AgentPlayground included), plus a vector-icons rule replacing text glyphs.

## Impact

- **Code**: `ChatViewModel` (remove seeded greeting; expose greeting/suggestions state), `ChatScreen` (home composable, chips, vector icons, haptics), `MainActivity` (screen transition wrapper), Setup/Permissions/AgentPlayground (token + icon sweep).
- **Dependencies**: one new — `androidx.compose.material:material-icons-extended` via BOM.
- **Specs**: new `home-greeting`; `ui-design-tokens` delta.
- **Risk**: icons-extended APK size (mitigated by R8), greeting logic must not fire on restored conversations.
