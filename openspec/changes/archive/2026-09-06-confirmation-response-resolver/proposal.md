## Why

When `TaskState.expectedSemantics == CONFIRMATION`, the user's follow-up utterance is routed through the same full conversational `ConversationSession` path as every other turn. In `AWAITING_CONFIRMATION`, the 2B on-device audio encoder is uncertain about short affirmation/negation clips ("No", "Haan", "Cancel"), causing the model to fall back to its prompt context and regenerate the confirmation question as plain prose (`DirectResponse`) rather than `ask_user`. Because `endedInAskUser == false`, `AssistantSession` exits the follow-up loop and silences the microphone — the confirmation is lost and the user has to restart the interaction.

The constrained output that already exists for tool grammars (GBNF via `GrammarBuilder`) is not applied to this follow-up turn. Applying a three-value output constraint (`CONFIRMED | DECLINED | UNKNOWN`) to the confirmation round eliminates the failure mode structurally: the model cannot output a question, and ambiguous audio maps to `UNKNOWN` which re-prompts correctly.

## What Changes

- Introduce `ConfirmationOutcome` enum (`CONFIRMED`, `DECLINED`, `UNKNOWN`) in `core:conversation`.
- Introduce `ConfirmationResolver` in `core:assistant` — a stateless inference wrapper that accepts audio bytes (DirectAudio) or a transcript (STT-Transcribe path) plus the pending question text, and returns `ConfirmationOutcome` via a constrained GBNF generate call on the existing `LiteRtLlmEngine`.
- `AssistantSession.handleFollowUpLoop()` branches on `taskState.expectedSemantics == CONFIRMATION`: instead of routing follow-up audio through a new full `ConversationSession`, it calls `ConfirmationResolver` and performs a deterministic state transition based on the outcome.
- `CONFIRMED` → advance `ContactResolution.confirmed = true` via `ConversationManager`; next LLM turn runs with `call_contact` unblocked in grammar.
- `DECLINED` → clear `taskState` and `pendingVoiceAsk`; speak a cancellation TTS phrase; exit loop.
- `UNKNOWN` → re-arm microphone; re-speak the original question; remain in `AWAITING_CONFIRMATION` state.
- The resolver uses DirectAudio input when the current strategy is `DIRECT_AUDIO`; it uses the STT transcript when the strategy is `STT_TRANSCRIBE`. No additional Whisper invocation is introduced for DirectAudio devices.
- The resolver prompt is stripped — no conversation history, no tool list, no full system prompt. It contains only the pending confirmation question, a description of the three semantic outcomes with multilingual examples, and the constrained grammar.
- `ExpectedResponseSemantics.CONFIRMATION` on `TaskState` gains its first concrete consumer; the enum value was previously computed but never branched on.

## Capabilities

### New Capabilities
- `confirmation-response-resolver`: Isolated, grammar-constrained inference operation that classifies a user's spoken or transcribed follow-up as `CONFIRMED`, `DECLINED`, or `UNKNOWN` when `TaskState.expectedSemantics == CONFIRMATION`. Owns the input routing, prompt construction, grammar constraint, and outcome mapping. Stateless — no persistent KV context.

### Modified Capabilities
- `action-confirmation`: The voice confirmation path changes from model-driven conversational interpretation (current) to resolver-driven deterministic classification (proposed). The requirement "no keyword matcher or app-side language interpretation" is preserved — the resolver is an LLM inference call, not a regex. The `UNKNOWN` outcome and its re-prompt loop are new behavior not currently specified.
- `voice-pipeline`: The follow-up loop in `AssistantSession` gains a branch point at `expectedSemantics`. The existing `handleFollowUpLoop` contract — gated microphone capture, TTS-first, round limit — is preserved. Only the processing of the captured audio in `AWAITING_CONFIRMATION` state changes.

## Impact

- `core:assistant` — `AssistantSession.handleFollowUpLoop()` modified; new `ConfirmationResolver` class added.
- `core:conversation` — new `ConfirmationOutcome` enum; `ConversationManager` gains a `confirmContactResolution(confirmed: Boolean)` method callable by `AssistantSession` to advance `ContactResolution` state without creating a new `ConversationSession`.
- `core:llm` — no changes; `LiteRtLlmEngine.generate(prompt, grammar)` already accepts a grammar parameter.
- No new model downloads. No new persistent KV contexts. No changes to the chat confirmation path (`source == TEXT` / `PendingConfirmation` deferred). No changes to STT, TTS, or tool declarations.
