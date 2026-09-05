# Tasks: fix-npu-turn-context

## 1. Context-preserving KV reset (core:llm)
- [x] 1.1 `LiteRtLlmEngine`: retain the last `AgentConfig` used for `startConversation()` and the
      recent conversation turns (or accept a replay callback) so `generate()` can rebuild context.
- [x] 1.2 Replace the blank `createConversation()` reset: on KV exhaustion, re-create the
      conversation with the retained `AgentConfig` (system instruction preserved) and replay the
      most recent turns within a replay budget (e.g. last 2–3 turns, bounded so replay + new
      prompt fits `activeKvCapacity` with the requested `maxTokens` headroom).
- [x] 1.3 If replay does not fit (first turn too large), reset without replay but WITH the
      `AgentConfig`, and log that context was dropped (observable, last resort).
- [x] 1.4 Emit a `ConversationEvent`/log marker when a reset occurs so the UI/logs show
      "context compacted" rather than silently losing memory.
- [x] 1.5 Unit tests: reset preserves system instruction; replay bounded by budget; replay
      skipped-but-configured fallback; reset triggers at correct threshold.

## 2. Backend-aware output budget (core:conversation)
- [x] 2.1 Revert blanket `maxOutputTokens ?: 512` to backend-aware: 256 when the active backend
      is NPU, 512 otherwise (engine exposes active backend via `LlmModelState`).
- [x] 2.2 Unit tests for the budget selection.

## 3. Compact tool prompting (core:conversation)
- [x] 3.1 Inject full tool schemas only on the first turn of a conversation and after any
      conversation reset; follow-up turns send user message + compact turn context only.
- [x] 3.2 Ensure capability/grammar scoping (`GrammarBuilder`) still applies on every turn where
      tools are available (schemas vs grammar are distinct concerns — grammar stays per-turn).
- [x] 3.3 Unit tests: schema appears exactly once per conversation segment; grammar present on
      tool-capable turns; reset re-injects schemas on the next turn.

## 5. Voice call-contact fixes (device-log findings, 2026-09-04)
- [x] 5.1 Placeholder phone detection in `ConversationSession.hasValidPhone`: treat as INVALID
      any `phone_number` that is `"..."`, `"…"`, `null`-like, contains no digits, or has < 5
      digits. Invalid phone ⇒ fall into the existing `lookup_contact` resolution path.
- [x] 5.2 Resolve-before-confirm ordering: when `call_contact` arrives without a valid phone,
      run contact resolution FIRST and drive the verbal-confirmation gate from the RESOLVED
      contact ("Call Suraj's Mom?") — never ask the user to confirm a call whose number is
      unresolved/hallucinated.
- [x] 5.3 Session-level candidate registry: keep `lastContactCandidates` (id → contact) across
      turns and engine context compactions; `call_contact` with `candidate_id` resolves against
      (1) taskState candidates, (2) the registry, (3) name re-query (last resort). Registry is
      only replaced by a new lookup and cleared on session close.
- [x] 5.4 DirectResponse turn must not clear an unresolved `ContactResolution` taskState
      (`ConversationSession` ~line 562): only clear when there is no pending multi-candidate
      decision; keep the candidate list until the call completes or session closes.
- [x] 5.5 Disabled-tools prompt rewrite: replace "explain to user if requested" with a scripted
      response template ("This tool needs the <permission> permission — ask the user to grant
      it in Settings") so the model cannot claim the capability does not exist.

## 6. NPU KV capacity clamp (regression guard)
- [x] 6.1 Restore a conservative NPU KV clamp in `LiteRtLlmEngine`: when the active candidate is
      NPU, cap `activeKvCapacity` at the AOT graph's real context (conservative default 1280
      until container metadata provides the true value) instead of the requested 8192 — the
      compaction guard must not compute against a capacity the native graph cannot serve.
- [x] 6.2 Keep this as an interim measure; final fix is metadata-derived capacity (tracked in
      add-npu-backend-support task 8.4).

## 7. Voice activation context replay (AssistantSession)
- [x] 7.1 On a new voice activation (DIRECT_AUDIO start), replay the most recent turns of the
      previous voice conversation into the fresh engine conversation (reuse the compaction
      replay mechanism/budget) so the assistant remembers across activations.
- [x] 7.2 Keep schema injection semantics: a fresh engine conversation is a new segment, so
      tool schemas are re-injected on the activation turn (consistent with
      conversation-manager spec).
- [x] 7.3 Unit test: activation after a previous voice session produces a conversation whose
      first prompt includes replayed recent turns + schemas.

## 8. Polish
- [x] 8.1 `ToolCallParser` fallback 2: strip wrapping quotes (`"..."`) from the recovered
      natural-language response.

## 9. Microphone availability resilience (device-log findings, 2026-09-05)
After a debug reinstall, runtime permissions (RECORD_AUDIO/CAMERA) are revoked. Every voice
activation then crashes the turn: `AudioFlinger could not create record track, status: -1`
(AudioRecord error -20) → `IllegalStateException: startRecording() called on an uninitialized
AudioRecord` → "Turn failed with unhandled exception". The app must detect, surface, and
recover from mic unavailability instead of crashing the turn.
- [x] 9.1 `AssistantSession`/voice strategy selection: before choosing DIRECT_AUDIO, check
      `RECORD_AUDIO` grant status; if not granted, do NOT construct an AudioRecord — surface a
      "microphone permission needed" event that triggers the existing permission flow
      (SetupScreen/PermissionsScreen).
- [x] 9.2 `AndroidAudioRecordReader` (AudioRecorder.kt:44): after constructing `AudioRecord`,
      check `state == STATE_INITIALIZED`; if not, `release()` and throw a typed
      `MicUnavailableException(reason)` (PERMISSION_DENIED / BUSY / INIT_FAILED) instead of
      letting `startRecording()` throw raw `IllegalStateException`.
- [x] 9.3 `AudioRecorder.arm()`/`recordGatedUtterance`: catch `MicUnavailableException`, cancel
      the turn gracefully with a user-facing message ("Microphone unavailable — grant mic
      permission"), and log the reason. Never propagate as an unhandled exception.
- [x] 9.4 Unit test: uninitialized AudioRecord path yields typed exception + graceful turn
      failure (mock/fake the reader); permission-check gate prevents recorder construction.

## 10. VAD robustness — premature end-of-speech + phantom onset (device-log findings, 2026-09-05)
Two failure modes observed in one log line (`rms=857 floor=712 speechTh=1531 silenceTh=1044
speechDetected=true silentFor=702ms → completion at 1661ms total`):

PRIMARY — premature end-of-speech while the user is still talking:
- `silenceDurationMs = 700` (AudioRecorder.kt:119) is shorter than natural inter-word/clause
  pauses, so the recording ends mid-sentence.
- `silenceThreshold = maxOf(peakRms * 0.20f, noiseFloor * 1.5f, 250f)` is derived from the
  GLOBAL peak of the utterance: after any loud word, all softer continuation (trailing
  syllables, quiet speech) sits below 20% of that peak and is classified as "silence" — the
  700ms clock runs during actual speech. In the log, rms=857 (real audio energy) was below
  silenceTh=1044 (inflated by noiseFloor 712 × 1.5), ending the capture at 1.66s.

SECONDARY — phantom onset: `speechTh = maxOf(noiseFloor * 2.2f, 500f)` with weak floor
calibration lets a single ambient burst (one chunk > ~500 RMS) start the speech timer; the
noise capture is then sent to the NPU model, which hallucinates a response.

- [x] 10.1 Silence window: raise `silenceDurationMs` default 700 → **1200ms** (tunable knob;
        first parameter to adjust on-device if responsiveness feels sluggish).
- [x] 10.2 Silence threshold from ROLLING recent peak: replace global `peakRms * 0.20f` with
        the max RMS of the last ~1.5–2s window × **~0.12f**, so softer continuation after a
        loud word still counts as speech. Cap the contribution of the adapted noise floor so
        an inflated floor (e.g. 712) cannot push silenceTh above recent-speech levels
        (e.g. silenceTh = max(recentPeak * 0.12f, 250f), and require rms > noiseFloor * 1.2f
        as an additional "still speaking" condition rather than feeding floor*1.5 into the
        threshold).
- [x] 10.3 Sustained onset (kept from earlier finding): speechDetected = true only after
        speech-level RMS persists ~250–300ms (N consecutive chunks), not one chunk.
- [x] 10.4 Noise-floor calibration (kept): sample ambient RMS unconditionally for the first
        ~300ms; keep EMA decay afterwards; onset floor ≥ ~800 RMS absolute.
- [x] 10.5 Minimum utterance duration: total detected speech < ~700ms ⇒ treat capture as noise,
        return empty audio (extend the existing `!speechDetected` empty-return), re-arm listening.
- [x] 10.6 AssistantSession last-line defense: empty/too-short capture ⇒ skip the LLM turn
        entirely, surface "I didn't catch that — try speaking again", never generate a response
        from noise.
- [ ] 10.7 On-device tuning pass: verify normal commands trigger on the first word AND long
        multi-clause sentences are not cut off; record chosen silenceDurationMs /
        threshold factors in this change's notes (these are feel knobs — expected to be tuned).
- [x] 10.8 Unit tests: soft trailing word after a loud word does NOT end the utterance; 700ms
        pause inside a sentence does NOT end it; 1200ms pause does; single noise burst does not
        trigger onset; sub-minimum utterance returns empty audio; AssistantSession skips LLM on
        empty audio.


## 11. NPU token-budget death spiral (device-log findings, 2026-09-05)
Device logs: on NPU, the compaction guard fires on EVERY turn ("KV cache nearing capacity
573/1280", "662/1280") and replay never fits ("replay did not fit in budget (332 tokens);
context dropped"). Cause: the mandatory per-segment prompt (system ≈80 + tool schemas ≈540
tokens, 1743 chars) plus audio input consumes ~660 of the 1280-token clamped KV before the
model generates; the guard (used + prompt + maxTokens + 128 ≤ capacity) therefore resets every
turn, drops context every time, and re-injecting schemas refills the cache — an endless loop.
Symptoms: generic/rambling replies on "Call Mom", hallucinated `lookup_contact(query:
"contacts")`, amnesia between turns.
- [x] 11.1 NPU compact tool-schema mode: when the active backend is NPU, inject a compact tool
      list (tool names + argument names only, no descriptions — target ≤ ~150 tokens) instead of
      the full 1743-char schema block. Same injection points as the compact-prompting work (first
      turn / after reset), full schemas unchanged on GPU/CPU.
- [x] 11.2 Compact NPU system instruction: a shorter system-prompt variant for NPU that keeps
      persona + tool-JSON response contract but drops verbosity (target ≤ ~50 tokens).
- [x] 11.3 Empirical NPU graph-context spike (replaces the guessed 1280 clamp): on SM8750 with
      the Qualcomm E2B model, find the real AOT graph context by attempting init/generation at
      increasing maxNumTokens (e.g. 1280 → 2048 → 4096) and recording where the native runtime
      fails; update `NPU_DEFAULT_KV_CAPACITY` (and the TODO for task 8.4) from the measured
      value. If the graph tolerates ≥ 2048, the per-turn pressure halves.
- [x] 11.4 Compaction guard retune: with the compact prompt, verify a 5-turn multi-tool
      conversation produces ZERO compaction resets in steady state (target math: compact schemas
      ~150 + system ~50 + audio ~100 + turns ≤ 400 < capacity − maxTokens − 128). If resets still
      occur every turn, raise the observed clamp per 11.3 or reduce reservation.
- [x] 11.5 Auto-lookup directive: add to the system instruction (both variants): "When the user
      asks to call/message someone, immediately call lookup_contact with their name — do not ask
      for contact information. Only ask which contact when a lookup returns multiple matches."
- [x] 11.6 Tests: compact schema mode token budget (< ~150 tokens est.); auto-lookup directive
      present; guard no-reset math with compact prompt.
- [ ] 11.7 Device validation (SM8750 + E2B): "Call Mom" voice flow — model calls lookup_contact
      unprompted on the FIRST turn, resolves/asks only if ambiguous, confirmation names the
      contact; 5-turn conversation without compaction resets; logcat excerpt attached to notes.

## 12. Voice Session Statelessness
- [x] 12.1 Voice-session statelessness enforced (SPEC VIOLATION, device log 2026-09-05): turn 2
      of a voice session started with `app history turns count = 5` and the model answered from
      turn 1's pending contact question, but conversation-manager spec.md:75 requires
      `newVoiceSession()` — fresh empty context (maxTurns=1) — to be called on EVERY voice turn,
      discarding prior sessions. Audit AssistantSession/AssistantViewModel: the ConversationSession
      is apparently created once per UI session and reused across startTurn() calls. Each turn
      must go through conversationManager.newVoiceSession() (or equivalent fresh-context path).
- [x] 12.2 No cross-turn state in voice mode: pending questions, TaskState, candidate lists, and
      lookback data must not survive into the next voice turn; only within-turn tool iterations
      (lookup → ask → confirm) may carry state, per the maxTurns=1 session boundary. Verify the
      Section 5 lookback/commit path also starts empty each voice turn.
- [x] 12.3 Unit tests: two consecutive startTurn() calls in one AssistantSession use independent
      ConversationSessions (turn 2's context is empty — no history turns, no pending task state);
      within-turn iterations still share context (lookup → ask works in one turn).
- [ ] 12.4 Device validation: turn 1 "Call Mom" → contact question; stay silent for turn 2 (after
      Section 10 lands, no turn fires at all; until then, if a turn fires its context MUST show
      `app history turns count = 0/1` in the diagnostic log); confirm no cross-turn answer.
      NOTE: this incident also re-confirms Section 10 is unfixed — turn 2 captured 14.5s of
      non-user audio (silenceDurationMs still 701ms, floor drifted to ~1100, rms spikes 2082–4459
      crossed onset). Section 10 remains the fix for the phantom trigger itself.

## 4. Observability
- [x] 4.1 `ToolCallParser`: log (level INFO) whenever a truncation/natural-language fallback
      fires, including which fallback and the raw length. Behavior unchanged.
- [x] 4.2 Device validation on SM8750 + Qualcomm E2B model: two-turn contact conversation
      ("look it up" → "mom") retains context across any compaction; no generic-greeting regression.

## 13. Cross-activation replay of executed actions ("calling Mom again") (device-log findings, 2026-09-05)
Section 7.1 intentionally replays prior voice-conversation turns across activations so the
assistant remembers context. Side effect observed: after a session that executed
`call_contact`, the next activation's turn-1 replay included the user prompt "Call Mom" plus
the assistant's action execution — the model interpreted the fresh "Call Mom" as a repeat and
answered "calling Mom again". The replay mechanism is correct; its payload is not.
- [x] 13.1 `TurnEntry` gains an `executedAction` (or equivalent) marker set when the turn's
      assistant response triggered a real tool execution (call/message/send — anything with
      external side effects; read-only lookups stay replayable).
- [x] 13.2 `LiteRtLlmEngine.startConversation()` (and any cross-activation replay path):
      exclude `executedAction` turns from `computeReplayTurns` input — the model must never
      see a past side-effecting command as conversation history. Compaction replay
      (`compactAndResetConversationInternal`, in-session) keeps full turns.
- [x] 13.3 After replay selection on a fresh activation, `recentTurns` is NOT re-seeded from
      the replayed turns (currently line ~363 `recentTurns.addAll(selectedReplayTurns)`
      perpetuates history across sessions); replay is read-only input for KV prefill.
- [x] 13.4 Unit tests: executed-action turn excluded from activation replay but included in
      in-session compaction replay; read-only lookup turn still replayed;
      `recentTurns` not re-populated from replay on `startConversation`.
- [ ] 13.5 Device validation: execute "Call Mom" in one activation, then in a NEW activation
      say "Call Mom" again — no "again" phrasing; say "what did I just ask" in a new
      activation — generic/amnesiac response is acceptable for side-effecting history.

## 14. VAD continuation hysteresis — ambient bursts reset the silence countdown (device-log findings, 2026-09-05)
Device log: after "Call Mom", silence countdown (`silentFor=340ms`) was reset to 0ms three
times by ambient spikes (rms 1511–2519 vs floor ~600) crossing the continuation check
`rms >= silenceThreshold && rms > noiseFloor * 1.2f` — capture hung 17.5s. The single-chunk
continuation gate is the defect: any one 50ms ambient blip resets a 1200ms countdown.
NOTE this revisits 10.2: the fix there (keep noise floor OUT of silenceThreshold) was correct
for its failure mode (premature end-of-speech); this section fixes the opposite failure by
requiring SUSTAINED energy to count as "still speaking", not by raising thresholds.
- [x] 14.1 Continuation gate becomes sustained: `lastSpeechTime` updates only after N
        consecutive chunks (≈3 chunks / 250–300ms, matching the 10.3 onset gate) exceed
        `noiseFloor * 1.6f`; a single burst cannot reset the silence countdown. Threshold
        factors and the 1200ms `silenceDurationMs` stay as tuned in Section 10.
- [x] 14.2 Ensure genuine soft speech still sustains the gate: trailing syllables at
        ~0.12× recent peak must satisfy the multi-chunk gate (the recentPeak window from
        10.2 stays the reference, not the global peak).
- [x] 14.3 Unit tests: 1–2 ambient bursts above the gate do NOT reset `silentFor`; 3
        consecutive in-band speech chunks DO; 1200ms of true silence ends capture promptly
        (target: capture stops ≤ ~1.5s after speech ceases in a noisy room, per the 10.7
        device-log findings).
- [ ] 14.4 Device validation (combines with open 10.7): noisy-room "Call Mom" stops capture
        within ~1.2–1.5s of speech end; multi-clause sentence with inter-word pauses is not
        cut off; single-word "Mom"/"Yes"/"Three" replies (≥350ms) survive the min-utterance
        filter. If both 14.1 tuning and the min-utterance value conflict on-device, record
        final knob values in this change's notes.
- [x] 14.5 Minimum-utterance filter 700ms → 350ms (AudioRecorder.kt ~line 397): natural
        single-word confirmations and digits run 350–550ms and were dropped
        ("560ms < 700ms → empty audio"). Mitigation for the phantom-noise risk this raises
        is 10.3's sustained-onset gate, not a longer filter; cover with a unit test (a
        400ms in-band utterance passes, a 400ms single noise burst still returns empty).

