# modernize-composer

## Why

The chat input is a bordered `OutlinedTextField` row with two separate always-visible buttons — visually dated next to the modernized message surface delivered by `modernize-chat-surface`. The composer is the most-touched UI element in the app and the remaining gap to a Gemini/ChatGPT-feeling chat experience before the app-shell work (drawer/settings) begins.

## What Changes

- Replace the bordered text-field row with a borderless floating pill composer (rounded container, grows with multiline input up to a max height, no outline).
- Replace the separate mic + send buttons with a single morphing action button: send (▶) when text is present, mic (🎤) when input is empty and idle, stop (■) while a response is streaming.
- Add generation cancellation: the stop button calls a new `ChatViewModel.cancelGeneration()`, cancelling the in-flight collection and invoking the existing (currently unused by chat) `LlmEngine.cancel()`; the in-flight assistant message is finalized with the partial text received so far.
- Out of scope: top-bar/model-dropdown changes, drawer, settings, home state, voice pipeline internals (mic button reuses existing `startVoiceInput`/`stopVoiceInput`), persistence.

## Capabilities

### New Capabilities
- `chat-composer`: Floating pill input composer with state-driven morphing action button (send/mic/stop) and generation cancellation.

### Modified Capabilities
(none — the `chat-ui` requirements for "text input field" and "send button" and "mic button" remain satisfied by the new composer; behavior is unchanged, only presentation. The `chat-message-rendering` streaming requirement gains a user-facing cancel affordance.)

## Impact

- **Code**: `core/ui/.../ChatScreen.kt` (input bar section only), `ChatViewModel.kt` (job reference + `cancelGeneration()`), possibly `ConversationSession`/`ConversationManager` (ensure cancellation propagates cleanly to a final `Completed`/`Error`-free state without a stuck in-flight message).
- **Dependencies**: none — pure Compose + existing engine API.
- **Behavior**: users can stop a streaming response; partial text is preserved. No changes to tools, voice, persistence, or markdown rendering.
