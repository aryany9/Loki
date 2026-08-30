# modernize-composer — Design

## Context

Current composer (in `ChatScreen.kt`): a full-width `Surface` row containing a bordered `OutlinedTextField` (max 4 lines), a separate mic `IconButton`, and a separate send `IconButton` that is always visible. `ChatViewModel` exposes `messages`, `isRecording`; generation runs in a `viewModelScope.launch` coroutine inside `sendMessage` (anonymous job — no handle kept). `LlmEngine.cancel()` exists and is implemented in `LiteRtLlmEngine` but chat never calls it. Phase 2 added `isStreaming` on `ChatMessage`, which gives the composer an accurate "generation in progress" signal.

## Goals / Non-Goals

**Goals:**
- ChatGPT-style composer: one pill, one morphing action button.
- Stop a streaming generation; keep partial text.
- No new dependencies; reuse tokens from Phase 1 and state from Phase 2.

**Non-Goals:**
- Top bar / model dropdown, drawer, settings, home state (later phases).
- Voice pipeline changes — mic behavior reuses `startVoiceInput`/`stopVoiceInput` as-is.
- Persistent history, message editing/regeneration.

## Decisions

### D1: Morphing button state machine (single source: ViewModel state)
Button state derives from existing observable state — no new state machine class:

| Condition | Button | Action |
|---|---|---|
| generation in progress (`isStreaming`/`isThinking` on last message) | ■ Stop | `viewModel.cancelGeneration()` |
| `isRecording == true` | ■ Stop (recording) | `viewModel.stopVoiceInput()` |
| `inputText.isNotBlank()` | ▶ Send | send + clear |
| otherwise | 🎤 Mic | `viewModel.startVoiceInput()` |

Priority: stop-generation > stop-recording > send > mic. Implement as a small sealed/enum in `ChatScreen` computed from the two StateFlows; animate the swap with `AnimatedContent` (crossfade + scale) — stdlib Compose, no new dep.
*Rationale:* keeps truth in the ViewModel; UI is a pure function of state. Rejected: keeping separate buttons (current clutter), or a new `ComposerState` holder (over-engineering for two flags).

### D2: Cancellation — job handle + engine cancel, partial text preserved
`sendMessage` stores its launched `Job` in a `private var generationJob`. New `cancelGeneration()`: `generationJob?.cancel()` then `conversationManager.llmEngine.cancel()`, and finalize the in-flight message (`isStreaming = false, isThinking = false`) keeping whatever `text` has streamed. Because Phase 2 streams *cumulative partials into the message*, the partial text simply remains.
*Rejected:* only cancelling the coroutine (engine may keep native inference running — `LiteRtLlmEngine.cancel()` exists precisely for that); only calling `engine.cancel()` (coroutine would continue and `Completed` would overwrite the partial with full text).
**Edge case to handle:** after cancellation the flow collector stops mid-stream, so no `Completed` arrives — `cancelGeneration()` itself performs the finalize. The cancelled job's `collect` must not emit afterwards (structured concurrency guarantees this).

### D3: Pill composer — plain `BasicTextField` inside a `Surface` pill
Replace `OutlinedTextField` with `BasicTextField` in a `Surface` shaped with `LokiCornerTokens.inputBar`, `surfaceVariant` background, no border; grows with content up to `heightIn(max = 160.dp)` then scrolls internally. Multiline `lineHeight` from `LokiTypography.bodyLarge`. Placeholder via `decorationBox`.
*Rationale:* `OutlinedTextField`'s border/label machinery fights the pill look; `BasicTextField` + custom decoration is the standard pattern. Keyboard insets already handled (Phase 1 `imePadding`).

### D4: Button visuals — filled circular icon button
One 44dp circular button (`MaterialTheme.colorScheme.primary` for send/stop, `surfaceVariant`→`primary` gradient not used — flat). Icons: use text glyphs consistent with existing codebase style (▶/■/🎤) rather than introducing a vector-icon dependency.

## Risks / Trade-offs

- [Cancellation mid-tool-execution could leave a tool half-executed] → Stop button only enabled during `isStreaming`/`isThinking`; document that cancelling during a tool call aborts the turn (same as closing the app). Mitigate by testing cancel during `ToolExecuting` — finalize message with partial text and existing `toolResult` if present.
- [`engine.cancel()` during native inference may surface an exception in the cancelled coroutine] → already-suppressed by structured concurrency; assert no crash via unit test with a cancel-aware fake engine.
- [Text glyphs vary across devices] → acceptable for now (matches current codebase); vector icons can come with the Phase 5 polish pass.

## Migration Plan

One composable section swap + one ViewModel method. Old composer removed wholesale; no migration of state. Rollback = revert.

## Open Questions

None. Scope (composer-only, no top bar) confirmed with user.
