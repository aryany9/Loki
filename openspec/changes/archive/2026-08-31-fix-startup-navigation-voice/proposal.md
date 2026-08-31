# fix-startup-navigation-voice

Three coupled/coincident defects in how the app boots and how the user starts a voice session. (1) System back exits the app instead of returning to chat on secondary screens — `MainActivity` enum navigation has no back stack or `BackHandler`. (2) The app lands on the previous/just-created conversation on startup instead of a new-chat home, because `ChatViewModel` eagerly `createConversation()`s at startup and `allowBackup=true` restores prior chats — a conversation should be created only on the user's first message. (3) Voice start (chat mic or assistant long-press) gives no audio feedback that recording/listening has begun. They ship together as the coherent "app boots and talks to the user correctly" change; each keeps its own isolated tasks for granular rollback.

## Why

- Back navigation defect makes Settings/Model-Library/Playground/Permissions a dead-end that exits the app on one back press.
- Eager startup conversation creation + backup-restore lands the user on stale content instead of a fresh new-chat home, violating the "fresh-install = new chat by default" product posture.
- Missing start tone makes voice input ambiguous on its first millisecond.

## What Changes

- **Back navigation**: add a real back stack for the `AppScreen` enum navigation so system back walks destinations and exits only from the chat root. BackHandler wired so non-root screens return to chat.
- **Landing on new-chat home**: always present the empty home state on launch; never auto-open the most-recent conversation and never create a conversation until first message.
- **Lazy conversation creation**: create + persist a conversation only on the user's first message; prior chats remain reachable via drawer recents.
- **Voice start cue**: play a short (~250 ms) non-TTS, Gemini-like rising tone (runtime-synthesized via AudioTrack, no asset) at voice start for both the chat mic and assistant long-press, before recording/listening begins. Not on stop; not doubled on config change.

## Capabilities

### New Capabilities
- `audio-start-cue`: system SHALL play a short non-speech attention tone at the beginning of any voice-input session (chat mic or assistant long-press), regardless of STT/model path.

### Modified Capabilities
- `conversation-persistence`: conversation creation becomes lazy (on first message), not eager at startup; launch presents empty home.
- `app-shell`: system back navigation walks the destination stack; chat is the root.

## Impact

- **Code**: `MainActivity.kt` (back stack + BackHandler), `ChatViewModel.kt` (remove eager `loadInitialConversation()` create; lazy create on first send; recents flow still populated), new `core/sound/AudioCue.kt`, call sites in `ChatViewModel.startVoiceInput()` and `LokiVoiceInteractionSession`.
- **Assets**: none (runtime tone synthesis).
- **Dependencies**: none (framework `AudioTrack`).
- **Specs**: `conversation-persistence`, `app-shell`, `voice-interaction-ui`, `chat-ui` deltas.
- **Risk**: back-stack vs. readiness gate interplay; backup-restore interplay; tone double-play on config change. Each mitigated + unit-tested.

## Out of Scope

- Model-switcher dropdown, conversation rename/retention, Hilt-ifying ViewModels (separate backlog).
- Voice stop/success/error tones (separate follow-up).
- **User-customizable start tone**: v1 ships a fixed runtime-synthesized Gemini-like rising tone gated by a hidden `audioStartCueEnabled` flag. Let the user pick their own tone (or disable) is a deferred backlog item.
- Migrating to Compose Navigation (the enum + stack is sufficient).