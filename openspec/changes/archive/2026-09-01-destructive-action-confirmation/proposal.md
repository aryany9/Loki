# Proposal: destructive-action-confirmation

## Why

`CallContactTool` fires `Intent.ACTION_CALL` the instant the model emits a tool call — a misheard voice command or model hallucination places a real phone call with no human in the loop. Destructive actions (direct calls, and SMS when it arrives) need an explicit user confirmation that repeats back the exact recipient, works identically in chat and on the voice overlay, and — on the voice overlay — keeps the microphone open so the user can answer "yes" or "no" verbally, Gemini-style.

## What Changes

- Add a **confirmation gate** to the tool-execution path: tools can declare `requiresConfirmation = true` and a human-readable `describeAction(arguments)` (e.g. *"Calling Rahul Sharma at +91 98765 43210"*).
- `ConversationSession` gains a **PendingConfirmation** phase: when a gated tool is invoked, the session emits a new `ConversationEvent.ConfirmationRequired`, suspends the tool loop, and awaits a verdict via a new `respondToConfirmation(accepted: Boolean)` API. Denial or timeout produces a tool-result turn ("user denied / no response") so the model can react conversationally.
- **Voice overlay**: on `ConfirmationRequired`, TTS speaks the repeat-back, then the overlay returns to **Listening** (mic stays open) to capture the verbal yes/no; a visual pending-confirmation state distinguishes it from normal listening. Keyword match on the transcript resolves the verdict.
- **Chat**: the composer area / tool card shows a confirmation card with Confirm / Cancel buttons wired to `respondToConfirmation`.
- **Gate `call_contact`** (`CallContactTool`); explicitly keep `dial_number` ungated (ACTION_DIAL only pre-fills the dialer — the user still presses call). All future destructive tools (e.g. SMS send) MUST be gated.
- Auto-cancel with a friendly message if no response arrives within the timeout window.

## Capabilities

### New Capabilities
- `action-confirmation`: The confirmation gate mechanism — tool-level confirmation declaration, repeat-back emission, verdict awaiting/resolution, timeout handling, and denial semantics in the conversation loop.

### Modified Capabilities
- `tool-registry`: `Tool` interface gains `requiresConfirmation` (default false) and `describeAction(arguments)`; registry surfaces both to the conversation layer.
- `voice-interaction-ui`: Overlay MUST return to Listening after speaking the repeat-back, accept verbal yes/no, and render a distinct pending-confirmation visual state.
- `chat-ui`: Confirmation card UI (repeat-back text + Confirm/Cancel) for pending destructive actions.
- `local-android-tools`: `call_contact` is confirmation-gated; `dial_number` remains ungated by design.

## Impact

- **`core/tools`**: `Tool.kt` (2 new interface members with defaults), `ToolRegistry.kt` (expose confirmation metadata).
- **`core/conversation`**: `ConversationSession.kt` (pending-confirmation state, new `ConversationEvent.ConfirmationRequired`, `respondToConfirmation`, timeout), `ConversationEvent` definition.
- **`core/assistant`**: `AssistantSession.kt` (voice verdict capture: TTS repeat-back → Listening → yes/no keyword parse → verdict), `LokiVoiceInteractionSession.kt` (pending-confirmation visual state).
- **`core/tools/local`**: `CallContactTool.kt` (gate + describeAction).
- **`core/ui`**: `ChatViewModel.kt` + `ChatScreen.kt` (confirmation card state + buttons).
- No new dependencies. No manifest changes (CALL_PHONE already declared). Voice overlay remains active throughout — no new audio-session behavior beyond existing DirectAudio/STT listening.