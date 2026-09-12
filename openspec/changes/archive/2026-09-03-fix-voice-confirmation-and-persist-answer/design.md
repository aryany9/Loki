## Context

Loki uses a single-turn `VoiceInteractionSession` overlay coordinated by `AssistantSession`.
When a turn ends, the coroutine's finally block always reverts state to
`AssistantState.Idle`, which `LokiVoiceInteractionSession` renders as a static "Ready" text
label. So the last answer the user was reading disappears the instant TTS stops speaking.

Calls require confirmation. `CallContactTool` declares `requiresConfirmation = true`.
`LookupContactTool` returns only the first matching contact (it keeps `matches.first()` and
a count string, discarding the rest). So "Call Mom" makes Loki guess one person and
immediately ask "yes or no" instead of listing every "Mom" contact so the user can pick.

Voice confirmation capture uses a fragile wall-clock `delay(250)` then a fresh
`AudioRecorder` restart before the next listen. Loki's own TTS tail re-enters the
microphone, Whisper re-parses it as an unrecognized utterance twice, and the confirmation
auto-cancels with a hardcoded "cancelled" message, ignoring the user's real reply.
`ConfirmationKeywords.isEcho` guards against parsing the re-prompt text as a verdict, but
that only guards parsing, not capture: the TTS tail is still recorded and still burns a
listen attempt.

Chat-side confirmation already uses `CONFIRMATION_TIMEOUT_MS` (20 seconds) via
`withTimeout`, and on denial or timeout it feeds a denial tool-result back to the model so
it can reply conversationally. The voice path has neither the timeout nor the denial-result
behavior.

Constraints: minSdk 29; no new heavy dependencies; voice stays private-offline (Whisper is
the default STT; the direct-audio LLM path is the fallback only when the active model lacks
audio input capability). The broader audio capability layer (AEC/NS/AGC probing, input
presets, VAD ownership, barge-in from TTS DSP) is parked in the roadmap as a separate later
change. This change only fixes the observed behavioral symptoms, using a commit-window seam
that the later layer can build on.

## Goals / Non-Goals

**Goals:**
- Persist the last answer on screen after the turn ends (no "Ready" flash) until the next
  turn begins or the session dismisses.
- List all matching contacts for ambiguous calls and let the user pick; an exact single
  match keeps the existing direct-confirm flow.
- Replace the blind post-TTS delay and fresh-recorder restart with an audio-domain listen:
  a continuously armed microphone whose committed window is gated on the TTS lifetime
  (`isSpeaking` / `onDone`), so the user's reply is not clipped and the TTS tail is not
  captured as user input.
- Give voice confirmation the same bounded timeout as chat, and on expiry feed a denial
  result to the model so it replies naturally (no hardcoded "cancelled" terminal).

**Non-Goals:**
- No AEC/NS/AGC probing, no input-preset selection, and no VAD rework. Those land in the
  parked audio-capability-layer change. The commit window gated on TTS is already the right
  seam for that future layer.
- No new permissions and no manifest changes.
- No change to the chat confirmation card (it already has Confirm/Cancel buttons, timeout,
  and denial-result semantics).
- No direct-audio-to-LLM rework. Whisper-versus-model-audio routing stays as-is; only the
  confirmation listening semantics change.

## Decisions

### D1: Persist the answer with a terminal completed state, not a forced Idle

When a turn finishes successfully with a non-empty final response, `AssistantSession` no
longer resets to `Idle`. Instead the session holds a terminal completed state that carries
the final response text (rendered like the current Speaking state: response text visible,
equalizer settled). The state returns to `Idle` ("Ready") only when `startTurn()` begins
the next listen or when the session dismisses. Turns that end with no answer to show
(silence, error, empty speech) still fall back to `Idle` as today.

Rationale: the current finally block in `startTurn()` unconditionally writes
`AssistantState.Idle`, and the overlay maps `Idle` to a static "Ready" label. Keeping the
last `Speaking` state would work but is semantically wrong (TTS is no longer active) and
would break any consumer that keys on `Speaking` meaning "currently speaking". A distinct
terminal state is explicit, testable, and leaves the idle wipe to a real lifecycle event.

Alternative considered: let the UI remember the last response text separately from the
state machine. Rejected because it couples the overlay to turn history and duplicates
state that the session already owns.

### D2: Structured multi-match lookup plus prompt guidance

`LookupContactTool.execute` collects all unique matching contacts (deduplicated by
normalized name and number, capped at 10 to keep the voice list short) and returns a
structured list such as `contacts: [{name, number}, ...]` instead of the head-only
`{name, number, count}` shape. `buildSystemPrompt` gains guidance: when a requested contact
matches multiple people, call `lookup_contact`, present the list with numbers, ask which
one to call, and only then invoke `call_contact` for the single selection.

Resulting UX: a unique name keeps the current fast path (lookup, then one confirm "Call
name at number?"). An ambiguous name produces "Which Mom?" with the options, the user picks
one, and then the single selected call goes through the normal confirmation gate.

Alternative considered: make `call_contact` itself fan out across matches. Rejected
because the model cannot choose for the user, and a call is a destructive gated action
that must resolve to exactly one target. A flat unstructured string of names was also
rejected as fragile for the model to reformat.

### D3: TTS-gated commit window instead of a blind delay

Replace the voice confirmation loop's `delay(250)` plus fresh-recorder restart with a
continuously armed capture gated on TTS state:

1. Before speaking the repeat-back, arm a single recorder that keeps running through the
   TTS utterance.
2. Samples captured while `tts.isSpeaking()` is true are not committed as user input.
3. On the TTS `onDone` callback, flip to commit mode and begin accepting samples, using a
   short rolling lookback (on the order of 120 to 180 ms) so a promptly spoken reply is
   not clipped at its onset.
4. The existing energy VAD in `AudioRecorder` (700 ms silence, 4500 ms initial timeout)
   detects end of speech. No start-gap constant is needed.

On top of the capture fix, add a confirmation timeout that mirrors the chat
`CONFIRMATION_TIMEOUT_MS` (20 seconds). On expiry the voice path resolves the gate as
denied (equivalent to `respondToConfirmation(false)`), so the model receives the denial
tool-result and replies naturally. This drops the hardcoded post-re-prompt "cancelled"
terminal and unifies voice and chat timeout semantics.

Rationale: the echo guard only protects parsing, not capture. The TTS tail is recorded
today and merely classified unrecognized, which burns a listen attempt and produces the
"it didn't wait for me" experience. Gating the commit window on TTS state removes the
dead-air pause entirely, prevents TTS self-capture at the source of the problem, and lays
the exact seam the parked AEC/barge-in layer will later exploit.

Alternative considered: increase the delay to 700-800 ms. Rejected as still a brittle
blind pause with worse UX, and the fresh-recorder restart per attempt remains. Relying
purely on the echo guard was rejected for the capture-versus-parsing reason above.

### D4: Assistant-initiated navigation deep link (incidental, required by the flow)

`MainActivity` gains `onNewIntent` handling for the `openScreen` extra (PERMISSIONS,
MODEL_LIBRARY, AGENT_PLAYGROUND, MEMORY, SETTINGS) plus a `pendingOpenScreen` compose state
consumed by a `LaunchedEffect`.

Rationale: when the voice confirmation path hits a permission denial, `AssistantSession`
launches `MainActivity` with `FLAG_ACTIVITY_NEW_TASK` and an `openScreen=PERMISSIONS`
extra. If the activity is already alive, Android recycles it and does not redeliver the
intent through `onCreate`, so the navigation extra was silently dropped and the user landed
wherever they already were. `onNewIntent` is the standard fix and is required for the
voice flow's `PERMISSION_OPENED` outcome to be meaningful.

Alternative considered: revert the fix and keep the pre-existing behavior. Rejected: the
permission flow silently doing nothing when the app is open is a broken UX that this
change's confirmation work directly exercises.

Scope note: this was implemented alongside D1-D3 by the implementing agent and initially
went undocumented; it was retained after cross-verification because D3's flow depends on
it, and is now specified under the `app-shell` capability delta.

### D5: Follow-up listening turns after a question

Voice turns that end with a question (final response ending "?") do not terminate the
invocation. `AssistantSession.handleFollowUpLoop` runs up to 3 rounds: speak the question,
capture a follow-up utterance with the same TTS-gated armed-recorder pattern as D3
(20 s timeout per round), transcribe it, and feed the transcript back as a new user turn on
the same `ConversationSession` (`source = "VOICE_FOLLOW_UP"`), routing any
`ConfirmationRequired` events through `handleVerbalConfirmation` as usual. One retry
("I didn't catch that") per missed round; exhausted retries exit gracefully to the terminal
completed state. A new `AssistantState.AwaitingFollowUp(responseText)` keeps the question
text on the overlay with a listening indicator. The recorder is released unconditionally in
the loop's `finally`.

Rationale: disambiguation ("Which Mom?") is useless if the session ends after asking. The
single-turn invocation previously forced the user to re-invoke and repeat the entire
request. Capping at 3 rounds and gating on "?" keeps the change scoped to
question-answer flows, not general free chat.

Alternatives considered: full multi-turn conversation in one invocation (rejected — scope
creep and harder timeout semantics); requiring re-invocation (rejected — broken UX for the
D2 disambiguation flow this change introduces).

Scope note: implemented after device verification showed the assistant stating the
disambiguation question and then stopping; names-only listing guidance was tightened in the
same pass (numbers are never spoken, lists over 3 are summarized).

## Risks / Trade-offs

- [TTS tail still leaks into the lookback window, producing a spurious early result] ->
  Keep the lookback small (120-180 ms), below the shortest natural pause between the
  repeat-back and a reply. The existing echo/keyword guard re-checks the committed
  transcript before a verdict, and an unrecognized result still gets one re-prompt per
  the existing spec.
- [Continuous recording through TTS may capture DSP artifacts on lower-end devices] ->
  The parked audio-capability layer is the proper home for AEC/NS/AGC. This change only
  fixes gating, which is the dominant failure today. If artifacts persist, the parked
  layer can switch the input preset without changing this seam.
- [Timeout denial feeds the model, which might re-ask and re-trigger the gated call] ->
  The denial tool-result lands in the conversation context, so the model can respond
  appropriately. The bounded timeout limits how long the user waits either way.
- [Persisted answer lingers if no next turn fires] -> The overlay already shows a Dismiss
  button, and the OS tears the session down on dismiss or onHide, which routes through
  `cancelTurn()`.
- [Ambiguous list and model selection could drift out of sync] -> The list uses the same
  normalized names the model echoes back into `call_contact`, and the final call still
  goes through one confirmation gate, so a wrong pick is still caught before dialing.

## Migration Plan

Single change branch. Each behavioral fix has isolated tasks (answer persistence,
disambiguation, confirmation capture) so they can be reviewed and reverted independently.
No schema or data migration: the changes touch an in-memory state machine, a tool result
shape, and prompt text. Rollback means reverting the three `core/*` areas
(`AssistantSession.kt`, `LookupContactTool.kt` plus prompt guidance, and the confirmation
capture path in `AssistantSession.kt`/`AudioRecorder.kt`). Specs are updated in both the
change folder and the canonical `openspec/specs/` copies at archive time.

## Open Questions

- Should `lookup_contact` hard-cap matches at 10, or a different number? Confirm during
  implementation against real contact lists and the voice list length.
- Exact lookback window length for the commit window (proposed 120-180 ms). Tune on
  device against real TTS tail decay.
- Whether full barge-in (committing user speech that starts during the TTS tail, not
  just after `onDone`) is needed now or belongs to the parked audio-capability layer.
  Leaning: parked, since listen-through-TTS with commit-on-done already fixes the
  observed bug.

