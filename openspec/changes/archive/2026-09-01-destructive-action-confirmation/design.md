# Design: destructive-action-confirmation

## Context

`ConversationSession` runs a bounded tool loop: parse GBNF-constrained tool JSON → `toolRegistry.execute()` → append `ToolExecutionResult` turn → feed back to the model (branches at `ConversationSession.kt:165-215` handle `Success` / `PermissionRequired` / `Error`). `CallContactTool` executes `Intent.ACTION_CALL` unconditionally inside that loop. `AssistantSession` maps `ConversationEvent`s onto `AssistantState` (`Listening`/`Processing`/`Speaking`) and already keeps the mic open across state transitions (`sttEngine.startListening().collect` at `AssistantSession.kt:215` — `ListeningStopped` re-enters `Listening`, it does not tear down the session). Chat renders tool activity through `ChatViewModel` state + `ToolResultCard`.

The user requirement: before a destructive action, repeat back the name/number and await confirmation — **and on the voice overlay the mic must remain live** so the user answers verbally ("yes"/"no"), Gemini-style, without re-tapping anything.

## Goals / Non-Goals

- **Goals**: one confirmation mechanism shared by chat and voice; verdict captured verbally on the overlay without leaving Listening; denial/timeout visible to the model so it responds gracefully; minimal blast radius in the tool loop.
- **Non-Goals**: general "ask the model to confirm" prompting (weak, bypassable); biometric gating; SMS/MMS tools (future change, but MUST adopt this gate); changing `DialNumberTool` behavior; "don't ask again" memory of prior confirmations (deliberately deferred).

## Decisions

### D1: Confirmation is a tool-declared property, not a session-level list
`Tool` gains `val requiresConfirmation: Boolean get() = false` and `fun describeAction(arguments: Map<String, Any?>): String` (default: `"$name"`). The session asks the registry for confirmation metadata at parse time — no hardcoded tool list, so every future destructive tool opts in by declaring it. *Alternative rejected:* a session-side `Set<String> CONFIRM_TOOLS` — splits ownership of destructive semantics away from the tool.

### D2: PendingConfirmation is a session state-machine phase, driven by a CompletableDeferred
On a gated call the session: (1) emits `ConversationEvent.ConfirmationRequired(toolName, repeatBack, callId)`, (2) creates a `CompletableDeferred<Boolean>` stored in `pendingConfirmation`, (3) awaits it with `withTimeout(CONFIRMATION_TIMEOUT_MS = 20_000)`. `respondToConfirmation(accepted)` completes the deferred. Verdict `true` → execute the tool exactly as today; `false`/timeout → synthesize a `ToolExecutionResult` turn ("User declined the action." / "No response received; action cancelled.") so the model sees the outcome in-context and replies naturally. Only one pending confirmation at a time (the loop is sequential anyway).
*Alternative rejected:* callback/listener interfaces — three listener hops for one boolean; the deferred keeps the loop's sequential shape and is unit-testable with `runTest`.


### D4: Voice verdict = stay in Listening + keyword match on the next transcript
On `ConfirmationRequired`, `AssistantSession` speaks `repeatBack` via the existing TTS path (`Speaking` state), then transitions to `AssistantState.Listening(awaitingConfirmation = true)` **without stopping the recorder/session** — the existing flow already re-enters `Listening` on `ListeningStopped`, so the overlay mic stays live. The next `SttEvent` transcription while `awaitingConfirmation` is keyword-matched (yes-set: "yes", "yeah", "sure", "confirm", "ok", "do it"; no-set: "no", "cancel", "stop", "don't") case-insensitively; match → `respondToConfirmation(verdict)`. Non-matching utterance → re-prompt once ("Sorry — yes or no?"), then a second non-match falls through to the D2 timeout. Keyword sets live in a small `ConfirmationKeywords` object so localization (future `multilingual-voice`) extends them in one place.
*Alternative rejected:* Android SpeechRecognizer for the verdict — violates the privacy decision (offline-only voice).

### D5: Chat verdict = confirmation card with Confirm/Cancel
`ChatViewModel` exposes `pendingConfirmation: StateFlow<PendingConfirmation?>`; `ChatScreen` renders it as a card above the composer (repeat-back text + tool name + Confirm/Cancel buttons). Tapping either calls `viewModel.respondToConfirmation(...)`; the card disappears and the normal tool-result/assistant flow resumes. The existing `cancelGeneration()` also resolves any pending confirmation as denied — cancelling generation must not leave a half-open gate.

### D6: Overlay shows a distinct visual state
`AssistantState` gains `AwaitingVerbalConfirmation(repeatBack: String)`; the overlay renders the repeat-back text prominently, with the existing `VoiceEqualizer` in its reactive Listening mode plus a subtle pulse on the repeat-back text. The user can always see *what* they are confirming — the green "listening" indicator alone is not enough.

## Risks / Trade-offs

- **Keyword match misfires on noisy transcripts** → mitigated by one re-prompt + timeout; worst case the action is *not* performed (fail-safe direction).
- **`withTimeout` inside the suspend tool loop** → the deferred await is cancellable; `cancelGeneration()` also completes the deferred as `false` so no zombie confirmation survives cancellation.
- **TTS + mic simultaneity** → the repeat-back plays through `Speaking` *before* returning to `Listening`; the recorder is already live during TTS today (existing `Speaking` → `Listening` transitions), so no new echo handling is introduced. If device echo proves noisy in manual testing, insert a short mute window after TTS completes — flagged as a manual-test checkpoint, not built speculatively.

## Migration Plan

Additive only: new interface members have defaults, so all 9 existing tools compile unchanged; only `CallContactTool` opts in. No persisted-state migration (pending confirmations are in-memory by design).

## Open Questions

- None. SMS gating lands with the future SMS tool change; `ConfirmationKeywords` localization lands with `multilingual-voice`.