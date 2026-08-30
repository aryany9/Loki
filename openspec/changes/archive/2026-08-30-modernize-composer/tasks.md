## 1. ViewModel — cancellation

- [x] 1.1 In `ChatViewModel`, keep a reference to the generation coroutine (`private var generationJob: Job?`) assigned in `sendMessage`
- [x] 1.2 Add `cancelGeneration()`: cancel `generationJob`, call `conversationManager.llmEngine.cancel()`, and finalize the in-flight assistant message (`isThinking = false`, `isStreaming = false`, keep partial `text` and any `toolResult`)
- [x] 1.3 Add a unit test: start generation with a cancel-aware fake engine → call `cancelGeneration()` → assert message finalized with partial text and no streaming/thinking flags, and engine cancel was invoked

## 2. Composer UI

- [x] 2.1 Replace the `OutlinedTextField` input bar with a borderless pill (`BasicTextField` in a `Surface` using `LokiCornerTokens.inputBar`, `surfaceVariant` background, `heightIn(max = 160.dp)`, placeholder via `decorationBox`)
- [x] 2.2 Replace the separate mic + send buttons with a single morphing action button implementing the state priority (stop-generation > stop-recording > send > mic), animated with `AnimatedContent`
- [x] 2.3 Wire the stop-generation action to `viewModel.cancelGeneration()`; keep stop-recording wired to existing `stopVoiceInput()`

## 3. Validation

- [x] 3.1 `./gradlew test :app:assembleDebug` passes
- [x] 3.2 Manual: idle → mic shown; typing → send shown; sending → stop shown; stop → partial text kept, composer returns to idle; recording → stop-recording shown
- [x] 3.3 Manual: multiline input grows the pill and scrolls beyond max height; keyboard insets still correct; dark/light themes respected
- [x] 3.4 Run `openspec validate modernize-composer` and confirm all tasks complete
