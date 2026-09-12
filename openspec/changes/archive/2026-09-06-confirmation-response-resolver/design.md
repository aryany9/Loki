## Context

The follow-up loop in `AssistantSession.handleFollowUpLoop()` handles all multi-round voice turns uniformly: after capturing audio, it creates a fresh `ConversationSession` and routes the audio through the full ReAct inference pipeline. This is correct for disambiguation turns (`SELECTION`) where the model needs to reason over contact candidates. It is wrong for confirmation turns (`AWAITING_CONFIRMATION`).

In `AWAITING_CONFIRMATION`, the user's utterance is a short yes/no reply. The on-device 2B audio encoder is unreliable for single-syllable audio clips. When uncertain, the model falls back to its prompt context and regenerates the pending question as plain prose (`DirectResponse`), not as `ask_user`. Since `endedInAskUser == false`, `AssistantSession` exits the follow-up loop and silences the microphone — the confirmation interaction is destroyed.

`TaskState.expectedSemantics` is a computed property on `ContactResolution` that returns `CONFIRMATION` when a contact is selected and the question has been asked (`isAsked == true`). This value is never read in `AssistantSession`. The enum was designed to be a branch point; this change makes it one.

## Goals / Non-Goals

**Goals:**
- Eliminate the failure mode where `AWAITING_CONFIRMATION` audio produces a `DirectResponse` question and exits the follow-up loop.
- Apply a grammar-constrained `CONFIRMED | DECLINED | UNKNOWN` output to the confirmation inference call, making the loop exit impossible by construction.
- Preserve full multilingual support — "Haan", "nahi", "go ahead", "please cancel" all handled by the audio LLM, not by a keyword matcher or STT transcription.
- Introduce no additional model downloads; DirectAudio devices stay on DirectAudio for confirmation.
- Introduce no second persistent KV context or new model lifecycle.

**Non-Goals:**
- Changing the chat confirmation path (`source == TEXT`, `PendingConfirmation` deferred, `[Confirm]`/`[Cancel]` UI buttons) — unaffected.
- Rewriting the follow-up loop mechanics (gated microphone, TTS-first, round limit, retry-on-silence) — retained exactly.
- Handling `SELECTION` or `MISSING_SLOT` semantics — this change introduces the resolver for `CONFIRMATION` only; other semantics continue to route through the full conversational path.
- Touching `GrammarBuilder`, `ToolRegistry`, or tool declarations.

## Decisions

### D1 — Branch on `expectedSemantics` in `handleFollowUpLoop()`

`AssistantSession.handleFollowUpLoop()` reads `conversationManager.taskState?.expectedSemantics` at the top of each round. When `CONFIRMATION`, it calls `ConfirmationResolver.resolve()` instead of creating a new `ConversationSession`. All other semantics take the existing full-session path unchanged.

*Alternative rejected:* Branch on `pendingVoiceConfirmation != null`. Less principled — couples the branch to the specific `ContactResolution` state shape rather than the semantic contract. `expectedSemantics` is the declared contract; branching on it is consistent with the existing architecture intent.

### D2 — `ConfirmationResolver` as a stateless function in `core:assistant`

`ConfirmationResolver` is a plain Kotlin object (or single-method class) in `core:assistant`. It accepts:
- `audioBytes: ByteArray?` (DirectAudio path — WAV encoded PCM)
- `transcript: String?` (STT-Transcribe path — already transcribed text)
- `question: String` (from `conversationManager.pendingVoiceAsk?.question`)
- `llmEngine: LiteRtLlmEngine`

It returns `ConfirmationOutcome`: `CONFIRMED`, `DECLINED`, or `UNKNOWN`.

Internally it calls `llmEngine.generate(prompt, grammar)` with:
- A stripped prompt containing only the question + semantic description with multilingual examples (no conversation history, no tool list, no system foundation)
- Grammar: `root ::= "CONFIRMED" | "DECLINED" | "UNKNOWN"`

*Alternative rejected:* `ConfirmationResolver` in a new `core:resolver` module. Adds a module layer for a single inference call. `core:assistant` already depends on `core:llm`; no new dependency edge is needed.

*Alternative rejected:* Expose a `newConfirmationSession()` factory on `ConversationManager`. Reuses `ConversationSession` plumbing but inherits its overhead (prompt assembly, grammar with tools, task state injection). The resolver's value is exactly that it doesn't do any of that.

### D3 — Input routing: DirectAudio → audio bytes; STT-Transcribe → transcript text

The `ConfirmationResolver` is called with the same audio that would have gone to the full session:
- DirectAudio strategy: passes `audioBytes` (the WAV-encoded PCM already captured), leaves `transcript = null`.
- STT-Transcribe strategy: passes `transcript` (already produced by Whisper for this round), leaves `audioBytes = null`.

No additional Whisper invocation is introduced for DirectAudio devices. The audio-capable Gemma 2B handles "Haan" / "nahi" / "sure" / "cancel" in any language better than Whisper transcription would, and forcing an STT pass would require Whisper to be loaded on devices that chose DirectAudio specifically because they have the audio model.

*Alternative rejected:* Always use Whisper STT for confirmation, regardless of strategy. Rejected for two reasons: (1) forces a Whisper download on DirectAudio devices, adding unnecessary model MB for a marginal use case; (2) Whisper is a transcription model optimised for well-formed speech — it handles casual multilingual affirmations ("Haan karo", "nope just cancel") worse than an audio-capable LLM interpreting the same with a classification prompt.

### D4 — Confirmation resolver prompt: stripped + semantically anchored

The prompt sent to the resolver contains:
1. The confirmation question that was asked (from `pendingVoiceAsk.question`)
2. Definitions of the three outcomes with multilingual examples:
   - `CONFIRMED` = clear affirmative (yes, haan, sure, go ahead, do it, karo, theek hai)
   - `DECLINED` = clear negative (no, nahi, cancel, don't, mat karo, ruk ja)
   - `UNKNOWN` = ambiguous, unclear, or unrelated
3. The output grammar constraint

No conversation history, no tool schemas, no system foundation prompt. This eliminates the failure mode where the model anchors on the prior question and re-generates it.

*Alternative rejected:* Minimal prompt without semantic anchors (just "classify: CONFIRMED/DECLINED/UNKNOWN"). The 2B model needs explicit vocabulary anchoring for reliable out-of-distribution inputs. Consistent with the pattern established in `modality-scoped-conversation-architecture` where vague framing causes compliance failures on small models.

### D5 — `CONFIRMED` state transition via `ConversationManager.confirmContactResolution()`

After `ConfirmationResolver` returns `CONFIRMED`, `AssistantSession` calls a new `ConversationManager.confirmContactResolution()` method that copies `ContactResolution` with `confirmed = true` and sets `taskState`. The follow-up loop then creates a new `ConversationSession` with the advanced `taskState`, where `call_contact` is unblocked in the grammar and the model invokes it immediately.

This preserves the principle that `ConversationManager` owns `taskState` mutation. `AssistantSession` requests the transition; it does not write `taskState` directly.

*Alternative rejected:* Emit a synthetic tool result and feed it into the existing LLM turn. More complex — requires constructing a fake `ToolResult` and injecting it into a session mid-stream. The state advancement path through `ConversationManager` is cleaner and already partially exists (`copy(confirmed = true)` is used in `ConversationSession` internally).

### D6 — `DECLINED` and `UNKNOWN` outcomes

- `DECLINED`: `AssistantSession` calls `conversationManager.clearVoiceTask()` (clears `taskState`, `pendingVoiceAsk`, `pendingVoiceConfirmation`), speaks a fixed TTS phrase ("Okay, I've cancelled that."), and exits the follow-up loop normally.
- `UNKNOWN`: No state change. `currentTurnEndedInAskUser` stays `true`. The loop re-arms the microphone and re-speaks the original question (`currentResponse` unchanged). The existing retry-on-silence and round-limit circuit breakers apply.

## Risks / Trade-offs

- **[Risk] 2B model produces malformed output outside the grammar** → LiteRT GBNF grammar enforcement is applied at the token generation level; the output is hard-constrained. If the engine returns an unexpected string (engine bug), `ConfirmationOutcome.from(result)` maps unknown strings to `UNKNOWN` as a safe default — the loop re-prompts rather than erroring.
- **[Risk] DirectAudio audio encoder deeply uncertain on very short clips** → `UNKNOWN` is the correct outcome; the loop re-prompts once (existing retry mechanism). This is strictly better than the current behavior where uncertainty causes a `DirectResponse` question and loop exit.
- **[Risk] `confirmContactResolution()` called when `taskState` is already `null`** → Guard: method is a no-op if `taskState` is not a `ContactResolution` with `selectedId != null && !confirmed`. No crash, no state corruption.
- **[Risk] Round-limit exhaustion during UNKNOWN loop** → Existing `MAX_VOICE_ROUNDS = 10` circuit breaker fires with "Let's stop here." — unchanged behavior.

## Migration Plan

1. Add `ConfirmationOutcome` enum to `core:conversation`.
2. Add `ConversationManager.confirmContactResolution()` and `clearVoiceTask()`.
3. Implement `ConfirmationResolver` in `core:assistant`.
4. Branch `handleFollowUpLoop()` on `expectedSemantics == CONFIRMATION`.
5. Update specs (`action-confirmation`, `voice-pipeline`) to reflect resolver-driven confirmation.
6. Validate on-device: "Call Mom" → question → "No" / "Haan" / "nahi" → correct outcome, correct TTS, correct mic state.

Rollback: revert the `handleFollowUpLoop()` branch. Chat confirmation path is unaffected throughout. No data migration required.
