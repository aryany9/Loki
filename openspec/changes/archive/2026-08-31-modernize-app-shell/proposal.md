# modernize-app-shell

## Why

Navigation is crammed into the chat TopAppBar (four inline icon buttons + a status badge) and there is no Settings screen — theme mode is persisted but has no UI. Gemini-style apps put navigation in a drawer and expose settings properly. With conversation persistence now landed (`persist-conversation-history`), the drawer's "recents" list has real data to show.

## What Changes

- Add a `ModalNavigationDrawer` (M3) as the app shell around the chat surface:
  - Header (app name + current model)
  - "New chat" action (creates a conversation via the existing store)
  - Recents: stored conversations (title, relative time), tap to load, long-press/delete affordance
  - Navigation entries: Model Library, Agent Playground, Permissions, Settings
- Simplify the chat TopAppBar: hamburger (opens drawer) + title + model status badge; the four inline icon buttons move into the drawer.
- Add a first-class **Settings screen** (new destination): theme mode selection (SYSTEM/LIGHT/DARK via `ThemeRepository`), app version, and links to Model Library / Agent Playground. Agent parameter editing stays in Agent Playground (out of scope).
- Model status badge becomes tappable to open a small model-info popover (current model name/state, retry on error, "Manage models" → Model Library). A full model-switcher dropdown is deferred — model switching remains in Model Library / Agent Playground for now.
- Out of scope: home/greeting empty state, suggestion chips, motion/transitions (Phase 5), model-switcher dropdown, account/profile, search.

## Capabilities

### New Capabilities
- `app-shell`: Navigation drawer (recents + destinations), simplified top bar, and a Settings screen with theme selection.

### Modified Capabilities
- `chat-ui`: The chat screen's navigation affordances move from inline TopAppBar buttons to the drawer; the chat screen hosts the drawer shell.

## Impact

- **Code**: `core/ui/.../ChatScreen.kt` (wrap Scaffold in `ModalNavigationDrawer`, drawer sheet composable, slim TopAppBar), new `SettingsScreen.kt` + `SettingsViewModel` in `core/ui`, `app/.../MainActivity.kt` (new `SETTINGS` destination in the `AppScreen` enum/when-block), reuses `ConversationManager.listConversations/loadConversation/deleteConversation` and `ThemeRepository`.
- **Dependencies**: none new.
- **Specs**: new `app-shell` spec; `chat-ui` delta (navigation affordances).
- **Risk surface**: drawer state vs. back handling (`PredictiveBack`), drawer refresh after new/switch/delete conversation, Hilt wiring for SettingsViewModel.
