# fix-navigation-and-startup

## Why

Two coupled shell/startup defects. (1) Pressing the system back button from Settings, Model Library, Agent Playground, or Permissions exits the app instead of returning to the chat home — the enum-based navigation in `MainActivity` has no back stack or `BackHandler`. (2) The app reopens/land-on the previous conversation and even eagerly creates a conversation before the user interacts, so a fresh install shows yesterday's chat rather than a new-chat home. Both are rooted in the same navigation-startup skeleton, so they ship together.

## What Changes

- **Back navigation**: introduce a real back stack for the `AppScreen` enum navigation so the system back button returns to the previous destination and, from chat (the root), exits. Add a `BackHandler` that degrades only when the stack is empty.
- **Landing on new-chat home**: on launch, always show the empty home state (greeting + suggestion chips), never force-open the most recent conversation.
- **Lazy conversation creation**: stop creating a conversation at startup. A conversation is created and persisted only on the user's first message. Prior to that, chat operates on an in-memory "draft" identity; previous chats remain reachable via drawer recents.
- **Reinstall/backup handling**: ensure a reinstall (or restored backup) shows the new-chat home by default; the restored conversations remain in the drawer recents but are not auto-opened.
- Out of scope: model-switcher dropdown, rename UI, retention cap (separate backlog items).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `conversation-persistence`: conversation creation becomes lazy (on first message), not eager at startup.
- `app-shell`: system back navigation returns through the destination stack; chat is the root.

## Impact

- **Code**: `MainActivity.kt` (back stack + BackHandler), `ChatViewModel.kt` (remove eager `createConversation()` in `loadInitialConversation`; lazy-create on first send), `ConversationManager`/`ConversationStore` (no behavioral change, but confirm create-from-empty semantics), `ChatScreen` (home state already renders on empty — ensure draft session has no stored id until first message).
- **Dependencies**: none.
- **Specs**: `conversation-persistence` + `app-shell` deltas.
- **Risk**: backup-restore interplay; ensuring recents still list restored conversations; making sure a destroyed-in-background Activity returns to a sensible root.