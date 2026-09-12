# Design: llm-first-voice-turn-control

## Context

`fix-npu-turn-context` (Sections 13–16) fixed replay payload, VAD hysteresis, the
candidate registry, and the ID-free speech list — but the TURN-CONTROL layer still runs
on prose heuristics inherited from the archived `fix-voice-confirmation-and-persist-answer`
change (`?`-gated loop, 3-round cap). Those heuristics were never promoted to a main spec,
so they were never re-examined. This change replaces them with a structured protocol and
closes the spec gaps that let them in.

## Goals / Non-Goals

- **Goal**: mic re-arm driven exclusively by model-declared intent; the model resolves
  every natural-language reply with full context; app never interprets language.
- **Goal**: model-readable and speech-facing text are formally separated in spec.
- **Non-Goal**: app-side verdict/keyword/ordinal parsing (explicitly forbidden).
- **Non-Goal**: cross-activation memory (voice stays stateless per conversation-manager
  spec); chat multi-turn behavior changes.

## Decisions

### D1: `ask_user` structured tool as the ONLY mic re-arm signal
The model already emits reliable JSON tool calls (every failure in the logs was in prose,
never in parsed tool calls). `ask_user(text)` is a no-side-effect tool; `ConversationSession`
emits `ConversationEvent.AskUser(text)` and completes the turn; `AssistantSession` re-arms
the mic iff the turn ended in AskUser. A turn ending in "?" prose WITHOUT ask_user does NOT
re-arm (DEBUG-logged, so device logs expose protocol adherence rate).
*Alternative rejected:* continue parsing prose shape (brittle, caused all three failures);
NLU classifier for "is this a question?" (violates the LLM-first directive — that IS
language interpretation).

### D2: In-activation pending-state continuity, injected — not remembered
Voice sessions stay per-turn fresh (maxTurns=1) for cross-activation statelessness, BUT
within one activation the manager-level state gains `pendingAsk` (question text + id-tagged
options presented). Each follow-up turn's task-state block carries: the pending question,
the options with ids/masked suffixes, and the user's verbatim reply — plus guidance to
resolve it and not re-ask when intent is clear. Cleared on task completion, timeout, or
activation close. The model stays the interpreter; the app is the notebook.
*Alternative rejected:* longer-lived voice sessions (breaks §12.2 statelessness and
cross-activation amnesia); app-side deterministic resolution (violates LLM-first).

### D3: Model-readable vs. speech-facing text boundary (fixes the §16 over-correction)
Tool results and task-state blocks (model reads, TTS never speaks) render
`[c3] Mom — ending in 95`. The speech-facing string (inside `ask_user(text)`, final
DirectResponse) stays ID-free. The §16 pre-TTS sanitizer remains the backstop.
*Alternative rejected:* ids in speech text (user hears "candidate c3" jargon — the original
Section 15 bug).

### D4: Uncapped loop with a generous safety limit
The 3-round cap is deleted; the loop runs while turns end in AskUser, with a 10-round
safety valve exiting with an app-rendered "Let's stop here". An unanswered round expires
via the existing `CONFIRMATION_TIMEOUT_MS` gate with a graceful sign-off.
*Alternative rejected:* keeping a small cap (multi-step flows legitimately need 4+ rounds:
disambiguation + confirmation + follow-up).

### D5: Spec alignment as first-class deliverables
- `action-confirmation`: full phone number requirement → masked-suffix repeat-back; the
  "No keyword matcher" rule is retained verbatim and extended to ALL voice NLU.
- `voice-pipeline`: follow-up capture requirement gains an explicit trigger contract
  (ask_user-driven, never prose shape).
- `conversation-manager`: in-activation continuity + AskUser event + pendingAsk lifecycle.
- The archived `voice-follow-up-turns` delta is superseded — its "?" trigger and 3-round
  cap are obsolete; archive specs are historical but this change's deltas override them
  wherever they conflict.

## Risks / Trade-offs

- **Protocol adoption by a 4-bit on-device model**: the model may sometimes end a question
  without `ask_user`. Mitigation: DEBUG logging of adherence; tool-result guidance; the
  failure mode degrades to today's behavior (turn ends, user re-taps), never to a hang.
- **Interpretation quality now rides on the model**: accepted deliberately — models
  improve, hardcoded parsers accrete edge-case bugs. Structural safety nets (Section 5
  confirmation flow, pre-TTS sanitizer) bound the damage.
- **pendingAsk stale state**: bounded by timeout, completion-clear, and activation-close
  lifecycle; unit-tested.

## Migration Plan

1. Ship ask_user tool + event + loop rewrite (spec deltas land with it).
2. Ship pendingAsk continuity + id-map restoration.
3. Device validation: "Call Mom" → options → "just mom" → one confirm → "haan, karo call"
   → call places, ≤ 2 LLM decision turns, no double-ask, mic always re-arms after a
   model question.
