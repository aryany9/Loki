## 1. ViewModel: recents + settings state

- [x] 1.1 `ChatViewModel`: expose `conversations: StateFlow<List<ConversationRecord>>`, refreshed on init, new/switch/delete conversation, and after each persisted turn; add `selectConversation(id)` calling `loadConversation` + mapping turns to messages
- [x] 1.2 New `SettingsViewModel` (Hilt): theme mode flow + `setThemeMode`, current model name/state from `ConversationManager`, injected via `ThemeRepository`/`ConversationManager`

## 2. Drawer shell

- [x] 2.1 Wrap `ChatScreen` Scaffold in `ModalNavigationDrawer`; build `ModalDrawerSheet`: header (app name + current model), "New chat", recents (tap-to-load, delete affordance, cap 20 displayed), destinations (Model Library, Agent Playground, Permissions, Settings)
- [x] 2.2 Wire drawer actions: new chat → `newConversation()`; recents → `selectConversation(id)`; destinations → existing/`onNavigateToSettings` callbacks; close drawer on every selection
- [x] 2.3 Add `BackHandler` closing the drawer before app exit; verify predictive-back behavior

## 3. Top bar + model popover

- [x] 3.1 Slim `ChatScreen` TopAppBar to hamburger + title + model badge; remove the four inline IconButtons
- [x] 3.2 Make `ModelStatusBadge` tappable: anchored info popover (model name, state, retry on error, "Manage models" → Model Library); dismiss on outside tap

## 4. Settings screen

- [x] 4.1 Create `SettingsScreen.kt`: theme mode three-option selector (SYSTEM/LIGHT/DARK) persisting via `SettingsViewModel`; current model section; links to Model Library/Agent Playground/Permissions; app version from BuildConfig
- [x] 4.2 Add `SETTINGS` to `AppScreen` + MainActivity `when` branch with `onNavigateBack` → chat; wire `onNavigateToSettings` from ChatScreen

## 5. Validation

- [x] 5.1 Unit tests: `ChatViewModel` conversations flow refresh on new/select/delete; `SettingsViewModel` setThemeMode persists
- [x] 5.2 `./gradlew test :app:assembleDebug` passes
- [x] 5.3 Manual: drawer open/close/back-gesture; recents tap loads conversation; new chat clears; theme switch applies immediately and survives restart; badge popover shows state/retry; all destinations reachable from drawer; edge-to-edge insets correct on sheet
- [x] 5.4 Run `openspec validate modernize-app-shell` and confirm all tasks complete
