# Tasks: llm-first-voice-turn-control

## 1. ask_user turn-intent protocol

- [x] 1.1 Add `ask_user(text)` tool (no side effects, requiresConfirmation=false) to the
      voice and chat tool sets; tool doc instructs the model to END its turn with ask_user
      whenever it needs information or a decision from the user
- [x] 1.2 `ConversationSession`: handle `ask_user` → emit `ConversationEvent.AskUser(text)`
      and complete the turn; no tool-execution round-trip
- [x] 1.3 `AssistantSession`: delete the `endsWith("?")` gates (voice and direct-audio
      paths) and the `rounds < 3` cap; rewrite the follow-up loop: speak response → if turn
      ended in AskUser, gated capture → next turn → repeat; safety cap 10 rounds with
      app-rendered "Let's stop here" exit
- [x] 1.4 DEBUG log `[LokiTurn] turn ended with question prose but no ask_user — mic stays
      off` for protocol-adherence observability
- [x] 1.5 Chat surface: render the AskUser question and accept the reply through the
      existing composer (no new UI beyond event handling)

## 2. In-activation pending-state continuity

- [x] 2.1 Extend the Section 15 manager-level state with `pendingAsk` (question text +
      id-tagged options presented); lifecycle: set when the model asks via ask_user about
      a pending task; cleared on task completion, capture timeout, session close
- [x] 2.2 Follow-up turns (VOICE / VOICE_FOLLOW_UP / DIRECT_AUDIO within an activation):
      rebuild the task-state block from pendingAsk — pending question, options with
      `[cN] id — name — ending in NN` entries, the user's verbatim reply, and guidance to
      resolve it and not re-ask when intent is clear
- [x] 2.3 Cross-activation amnesia test: a NEW activation starts with empty pendingAsk;
      §12.2 fresh-session semantics unchanged

## 3. Model-readable vs. speech-facing text boundary

- [x] 3.1 Restore candidate ids + masked suffixes in multi-match `call_contact` tool
      results and "Matching contacts:" task-state blocks (`[c3] Mom — ending in 95`)
- [x] 3.2 Keep the speech-facing options string (ask_user argument, final response)
      ID-free; pre-TTS sanitizer (§16) unchanged
- [x] 3.3 Update tool-result guidance: confirm via ask_user with masked suffix ("Shall I
      call Mom, the number ending in 95?"); full phone numbers never rendered

## 4. Tests

- [x] 4.1 Mic re-arm: "?" prose without ask_user does NOT re-arm; AskUser turn re-arms;
      no 3-round abort; 10-round safety cap exits gracefully
- [x] 4.2 Mid-string question ending with ask_user re-arms (regression for the
      "Which contact would you like to call? I see ..." failure)
- [x] 4.3 Continuity: follow-up prompt contains pendingAsk block + verbatim reply; the
      reply reaches the LLM unmodified (architectural assertion: no keyword/ordinal/
      suffix parsing exists in app code)
- [x] 4.4 pendingAsk lifecycle: cleared on completed call, timeout, session close; survives
      `newVoiceSession()` within an activation; empty on new activation
- [x] 4.5 Text boundary: tool result contains `[cN]` ids + masked suffixes; ask_user text
      and speech-facing list contain neither ids nor full numbers
- [x] 4.6 Update tests that asserted the "?" heuristic or 3-round cap

## 5. Device validation

- [x] 5.1 Voice: "Call Mom" → options via ask_user → "just mom" → model resolves, asks ONE
      confirm → "haan, karo call" → call places; no double-ask; mic re-arms every time the
      model asks; total flow ≤ 2 LLM decision turns
- [x] 5.2 Fresh activation with mid-string-question response re-arms the mic; logcat shows
      ask_user protocol adherence rate and zero app-side interpretation
