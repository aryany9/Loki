## Context

Loki resolves a per-turn voice-input strategy from the active LLM's capability metadata (`VoiceInputStrategyResolver`): `DirectAudio` (raw PCM → WAV → LLM) for audio-capable models like `gemma-4-E4B-it`, or `SttTranscribe` (Whisper → text) for text-only models. Round 1 already honors this split.

The multi-turn tail of a voice turn does not:

- `AssistantSession.handleFollowUpLoop` (disambiguation / any response ending in "?") calls `transcribeVerdict(sttEngine, …)` unconditionally. On `DIRECT_AUDIO` the Whisper runtime is not loaded, so the transcript is empty and captured speech is discarded ("I didn't catch that").
- When `ConversationSession.processUtterance` parses a tool call with `requiresConfirmation = true`, it emits `ConfirmationRequired` and suspends on a `PendingConfirmation` deferred. `AssistantSession.handleVerbalConfirmation` captures the reply, transcribes it via STT, and matches it against `ConfirmationKeywords` regex. With Whisper inactive this is an immediate silent auto-deny; even with STT, natural affirmations fail keyword matching.

An earlier change (`fix-voice-confirmation-and-persist-answer`) built the current confirmation state machine, a TTS-echo-gated recorder (`recordGatedUtterance`), and contact disambiguation listing. Its gated capture and disambiguation behavior are sound and are retained; its programmatic voice-verdict machinery is what this change replaces.

## Goals / Non-Goals

**Goals:**
- Route native audio to the LLM on every voice round (initial, disambiguation follow-ups, conversational confirmations) when the strategy is `DIRECT_AUDIO`.
- Make destructive-action confirmation conversational and model-driven on the voice path; remove the keyword/STT verdict machinery there.
- Keep the chat (`source = "TEXT"`) programmatic confirmation gate and `[Confirm]`/`[Cancel]` UI untouched.
- Keep Whisper as a real fallback for `STT_TRANSCRIBE` models and for failed direct-audio demotion.

**Non-Goals:**
- Changing the ReAct JSON tool-calling loop, `ToolRegistry` declarations, or `requiresConfirmation` semantics.
- Building a new confirmation UI or telemetry.
- Reworking TTS, VAD, or `AudioRecorder` capture behavior beyond reuse of `recordGatedUtterance`.

## Decisions

### D1 — Strategy-aware audio routing for all multi-turn rounds

`handleFollowUpLoop` receives the resolved strategy (or a `useDirectAudio` flag derived once in `startTurn` alongside the existing `sttEngine`/`modelManager` parameters).

- `DIRECT_AUDIO`: after capture, call `WavEncoder.pcmFloatsToWav(audioFloats)` and invoke `voiceSession.processUtterance(userInput = "", audioBytes = wavBytes, enableTts = false, source = "VOICE_FOLLOW_UP")`. STT is never consulted for routing; silent-buffer checks (`isSilentBuffer`) remain the gate for "no speech".
- `STT_TRANSCRIBE`: current behavior unchanged (transcribe → text turn).

Alternative considered: keep STT-first with WAV fallback when the transcript is empty (partially exists today). Rejected because it depends on an inactive Whisper runtime, adds latency, and the empty-vs-null transcript branches are exactly what produced the discarded 72,000 bytes.

The same routing applies inside the confirmation round: since confirmation is now conversational (D2), the user's reply to "Do you want me to call Mom at …?" arrives through the same follow-up loop — no separate confirmation capture path.

### D2 — Conversational (model-driven) confirmation on voice; programmatic gate on chat

- `ConversationSession.processUtterance` gates on source: the `requiresConfirmation` suspension block runs only for chat surfaces (`source == "TEXT"` or other non-voice sources). For voice sources (`"VOICE"`, `"DIRECT_AUDIO"`, `"VOICE_FOLLOW_UP"`), the tool call with `requiresConfirmation = true` is NOT executed and NOT channel-gated; instead a tool-result turn is appended instructing the model that it must first ask the user a confirmation question naming the contact and full phone number, and may invoke the tool in a later turn once the user verbally confirms. (This "coached deferral" keeps the loop terminating within `maxIterations` and gives the model an explicit, in-context instruction even if the system-prompt guidance was not followed.)
- `buildSystemPrompt` gains the confirmation contract: *before calling `call_contact`, always confirm by stating the contact name and full phone number in a question; only invoke `call_contact` once the user has verbally confirmed.*
- `AssistantSession` loses `handleVerbalConfirmation` and all `ConfirmationRequired` interception on voice turns; the existing follow-up loop (with D1 routing) carries the user's reply back to the model, which decides affirmation naturally ("yes, you are right", "sure", "haan kar do").
- `ConfirmationKeywords` is deleted once no consumers remain.

Alternatives considered:
- *Soft gate* (emit `ConfirmationRequired` informationally without suspending on voice): rejected — no consumer needs the event on voice, and keeping it invites dead UI code.
- *Keep regex as safety backstop*: rejected — the space of affirmations is open-ended natural language; a regex either over-matches or under-matches. The LLM has conversation context and outperforms keyword matching by construction. The residual risk (model executes without asking) is mitigated by the coached-deferral tool result above, which blocks first-attempt execution of gated tools on voice regardless of prompt adherence.

### D3 — Cleanup boundaries

- `ConfirmationKeywords.kt`: deleted (verify `ChatViewModel` uses buttons, not the matcher, before removal).
- `handleVerbalConfirmation` / `transcribeVerdict`: `transcribeVerdict` survives only if the `STT_TRANSCRIBE` follow-up path still uses it; otherwise inlined/removed.
- `recordGatedUtterance` and the TTS-gated capture pattern are retained and used by the follow-up loop so the assistant's own TTS tail is not ingested as the user's reply.
- Spec deltas modify `voice-input-strategies`, `voice-pipeline`, and `action-confirmation`; the timeout requirement in `action-confirmation` remains chat-scoped (voice has no pending confirmation to time out).

## Risks / Trade-offs

- [Model executes `call_contact` without asking first] → Coached deferral: the first voice-path invocation of a gated tool is always converted to a "ask first" tool-result turn, so execution requires at least one conversational round. Prompt contract reinforces it.
- [Model misreads an ambiguous reply ("maybe later") as confirmation] → Accepted trade-off; a phone call is low-stakes and recoverable. The prompt instructs asking a re-confirmation question on ambiguous replies rather than proceeding.
- [Follow-up loop max-iterations exhaustion during confirmation rounds] → Confirmation consumes one loop iteration; `maxIterations` headroom verified in tests.
- [Latency of re-sending conversation context with audio each round] → Native conversation context is already stateful (`startConversation`); only the new audio turn is appended. Monitored via existing turn diagnostics.
- [Regression in chat confirmation] → Chat path (`source = "TEXT"`) code is untouched; `ConversationSessionTest` keeps gate assertions for chat sources.

## Migration Plan

1. Land D1 (routing) + D2 (gate split + prompt) together — D1 without D2 still auto-denies confirmations; D2 without D1 cannot hear the reply.
2. Remove `handleVerbalConfirmation` / `ConfirmationKeywords` (D3) after voice flow validated.
3. Rollback = revert the change; chat confirmation is unaffected throughout.

## Open Questions

- None blocking. Optional follow-up: surface a "confirming…" assistant state on voice while awaiting the user's reply (currently the loop's `Processing` state covers it).
