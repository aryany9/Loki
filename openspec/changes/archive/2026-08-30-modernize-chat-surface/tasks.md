## 1. Dependencies

- [x] 1.1 Add `multiplatform-markdown-renderer-m3` (+ commonmark core) to `gradle/libs.versions.toml` and `core/ui/build.gradle.kts`
- [x] 1.2 Build `./gradlew :app:assembleDebug` to confirm dependency resolution

## 2. Conversation pipeline (streaming)

- [x] 2.1 In `ConversationSession`, pass an `onToken` callback that emits `ConversationEvent.GeneratingToken` with the cumulative partial response
- [x] 2.2 Add `isStreaming: Boolean` field to `ChatMessage` (default false)
- [x] 2.3 In `ChatViewModel`, handle `GeneratingToken`: upsert the in-flight assistant message with batched updates (e.g. conflate to ~50ms); on `Completed`, finalize the message (`isStreaming = false`)

## 3. Message rendering split

- [x] 3.1 Split `ChatBubble` into `UserMessageBubble` (end-aligned bubble, plain text, `LokiCornerTokens`) and `AssistantMessage` (full-width, no bubble background, `MaterialTheme.typography`)
- [x] 3.2 Render assistant text as Markdown via the new renderer, themed from `MaterialTheme`
- [x] 3.3 Render the thinking state as a pulsing-dot/shimmer animation (no spinner) while `isThinking` and no tokens have arrived
- [x] 3.4 Replace the "✓ Action executed" pill with an expandable tool-result card (header: tool name + success/error icon; collapsible payload body)

## 4. List behavior

- [x] 4.1 Use `ChatMessage.id` as stable `LazyColumn` item keys; auto-scroll only follows while near bottom during streaming
- [x] 4.2 Verify `SessionOverlay`/voice paths unaffected by the message-model changes (no UI change there)

## 5. Validation

- [x] 5.1 Unit tests: `ChatViewModel` streaming upsert behavior (Thinking → GeneratingToken(s) → Completed yields one finalized message)
- [x] 5.2 `./gradlew test :app:assembleDebug` passes
- [x] 5.3 Manual: send a prompt that yields markdown/code; verify full-width rendering, streaming growth, thinking animation, tool card expand/collapse, dark/light themes
- [x] 5.4 Run `openspec validate modernize-chat-surface` and confirm all tasks complete
