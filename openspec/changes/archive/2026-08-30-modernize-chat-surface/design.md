# modernize-chat-surface — Design

## Context

`ChatScreen.kt` renders every message through a single `ChatBubble` composable (tinted bubble both sides). `ChatMessage` has `isThinking` and `toolResult` fields. `ChatViewModel` collects `messages: StateFlow<List<ChatMessage>>` from `ConversationEvent`s. The streaming path exists end-to-end but is severed in the middle: `LlmEngine.generate(..., onToken)` supports per-token callbacks and `ConversationEvent.GeneratingToken(partial)` is declared, but `ConversationSession` passes `onToken = null`, so nothing emits token events.

Phase 1 delivered `LokiTypography`, `LokiShapes`/`LokiCornerTokens`, `LokiSpacing` — all consumed here; no new tokens required.

## Goals / Non-Goals

**Goals:**
- Gemini/ChatGPT-style assistant message presentation (full-width, no bubble, markdown).
- True token-by-token streaming in the chat UI.
- Animated thinking indicator; expandable tool-result cards.
- Reuse existing `ConversationEvent` pipeline — wire, don't rebuild.

**Non-Goals:**
- Composer redesign (Phase 3), home state (Phase 5), drawer/settings (Phase 4), persistence.
- Changing `ToolCallParser`, tool execution logic, or voice pipeline.
- Markdown in *user* messages (users send plain text only).

## Decisions

### D1: Markdown rendering — `multiplatform-markdown-renderer-m3` (Mikepenz)
Adds `com.mikepenz:multiplatform-markdown-renderer-m3` (plus its `commonmark` transitive core) via `libs.versions.toml`. It renders into Compose natives, respects `MaterialTheme` colors/typography, and supports extended spans/plugins for code blocks.
*Alternatives rejected:* `Markwon` (View-based, fights Compose); hand-rolled parser on `commonmark` (full control but high effort/maintenance for tables + code blocks); `compose-richtext` (less active maintenance). Fallback if the library blocks: custom `commonmark` AST → AnnotatedString renderer covering headings/bold/italic/lists/code only.

### D2: Assistant message = full-width column; user message keeps bubble
Split `ChatBubble` into `AssistantMessage` (full-width, `MaterialTheme.colorScheme.onSurface` text, no background container, markdown) and `UserMessageBubble` (end-aligned, `surfaceVariant`-style bubble via `LokiCornerTokens`, plain text). Reason: matches Gemini; assistant content (headings, code blocks) needs full width.

### D3: Streaming via existing events — wire `onToken` through
`ConversationSession` passes `onToken` that emits `ConversationEvent.GeneratingToken(cumulativePartial)`; `ChatViewModel` upserts the in-flight assistant `ChatMessage` (new `isStreaming: Boolean` field) instead of waiting for `Completed`. Throttle recomposition by batching token updates (~every 50ms or per-token with distinct message IDs — batched, since tokens can arrive at 100+/s).
*Alternative rejected:* bypassing ConversationManager with a direct `LlmEngine` call — would duplicate session/tool logic.

### D4: Thinking state — shimmer, not spinner
`isThinking` renders an assistant row with three pulsing dots or shimmering "Thinking…" text (simple `infiniteTransition` alpha animation — no new dependency). Replaces the current `CircularProgressIndicator` row.

### D5: Tool result card — expandable `Surface`
Replace the "✓ Action executed" pill with a card: header row (tool name + success/error icon) always visible; tapping toggles a collapsible body showing formatted result data (`remember { mutableStateOf(false) }` expanded state). Uses `LokiCornerTokens` + `surfaceVariant`.

## Risks / Trade-offs

- [Markdown lib adds APK weight / transitive deps] → single well-scoped library; proguard rules included; measure APK delta.
- [Streaming at high token rate causes recomposition storms] → batch updates in ViewModel (conflate with `sample`/manual buffer); only the streaming message recomposes (stable `LazyColumn` keys via `ChatMessage.id`).
- [Markdown inside streaming partial text may be malformed mid-stream] → renderer tolerates incomplete markdown (renders progressively); degrade gracefully — worst case a frame renders plain text.
- [Full-width assistant messages change `chat-ui` spec semantics] → delta spec included; voice overlay and conversation logic untouched.

## Migration Plan

Single change, additive rendering path. Sequence: markdown lib + message split (works with current behavior) → streaming wiring → thinking/tool cards. Rollback = revert; no data/schema changes.

## Open Questions

None blocking. Library pin version to latest stable at implementation time.
