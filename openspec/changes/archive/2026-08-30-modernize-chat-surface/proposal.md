# modernize-chat-surface

## Why

The chat surface still renders classic chat bubbles with plain text, a spinner for thinking, and a bare "✓ Action executed" pill — visually far from the modern Gemini/ChatGPT-style assistant experiences the project targets. Phase 1 (`modernize-ui-foundation`) established the token foundation; this change redesigns the conversation rendering itself.

## What Changes

- **BREAKING (visual)**: Assistant messages render full-width and bubble-less (typography-driven, no tinted bubble background); user messages keep a compact bubble aligned to the end.
- Assistant text renders as Markdown (headings, bold/italic, lists, inline code, fenced code blocks with monospace styling) via a new Compose markdown rendering dependency.
- Streaming output: tokens render incrementally as they are generated. Wires the existing-but-unused `ConversationEvent.GeneratingToken` flow — `ConversationSession` currently passes `onToken = null`.
- Thinking state replaces spinner-with-text with an animated shimmer "Thinking…" indicator on the assistant message row.
- Tool execution results render as an expandable card (tool name, status, collapsible result payload) replacing the static "✓ Action executed" pill.
- Out of scope: composer redesign, home/greeting state, nav drawer/settings, persistent history, motion/transitions.

## Capabilities

### New Capabilities
- `chat-message-rendering`: Markdown rendering of assistant messages, streaming token display, thinking-state animation, and tool-result expandable cards in the chat surface.

### Modified Capabilities
- `chat-ui`: Assistant responses change from "chat bubbles" to full-width bubble-less rendering; adds streaming/progressive display and tool-result card rendering requirements.

## Impact

- **Code**: `core/ui/.../ChatScreen.kt` (`ChatBubble` → split into `UserMessageBubble` + `AssistantMessage`), `ChatViewModel.kt` (handle `GeneratingToken`), `core/conversation/.../ConversationSession.kt` (pass `onToken`), `ChatMessage.kt` (add streaming state fields).
- **Dependencies**: one new library — Compose markdown renderer (`com.mikepenz:multiplatform-markdown-renderer-m3` + `commonmark` core), cataloged in `libs.versions.toml`.
- **Specs**: new `chat-message-rendering` spec; delta on `chat-ui` (bubble requirement MODIFIED).
- **Behavior**: response text now streams; no changes to tool execution logic, voice, or persistence.
