# Proposal: llm-first-voice-turn-control

## Why

Device logs (2026-09-05) exposed a systemic design flaw: the app infers conversational
intent by pattern-matching the model's PROSE — the least reliable interface an on-device
model has. Three verified failures:

1. **Mic never re-arms**: the follow-up loop is gated on `finalResponseText.endsWith("?")`
   (`AssistantSession.kt:346/447/516`) with a 3-round cap. A question placed mid-string
   ("Which contact would you like to call? I see ... Rushikesh's Mom.") ends with a period,
   so the mic never reopens; multi-step disambiguation flows exhaust the 3-round cap before
   the user's final "yes".
2. **The model cannot reference candidates**: after the Section 16 ID-free-list fix, the
   model never sees the id↔contact mapping and emits `call_contact(name: "Mom")` with no
   `candidate_id`, forcing a name re-query that re-matches duplicates and loops forever.
3. **The model re-asks for confirmation** ("Understood, calling Mom. Do you want me to
   proceed?") because each voice turn's context forgets that the model itself just asked.

Additionally, the main specs have drifted from the shipped decisions:
- `action-confirmation/spec.md` REQUIRES stating the **full phone number** in the voice
  confirmation question — contradicting the masked-suffix-only privacy decision (Sections
  15/16 of `fix-npu-turn-context`).
- No main spec governs WHEN the mic re-arms; the "?"-heuristic behavior exists only in an
  ARCHIVED change delta (`voice-follow-up-turns`), which is how it entered the code
  unchallenged.

## Product Philosophy (non-negotiable architecture directive)

**The LLM is the brain. The app is ears + mouth + structured memory.**

This is a Gemini alternative. All natural-language understanding — including "haan karo
call", "just mom", "the first one", "obviously" — belongs to the model. The app MUST NOT
interpret user language: no keyword verdict sets, no ordinal parsers, no suffix matchers.
The app's four jobs:

1. Transport audio/text (voice strategies, TTS)
2. Execute tools safely (Section 5 confirmation sequencing, pre-TTS sanitizer)
3. Know WHEN to listen (turn-intent signaling from the model — never prose shape)
4. Carry structured state so the model has full context (pending asks, candidate maps)

## What Changes

- **`ask_user` turn-intent protocol**: a structured, no-side-effect tool the model invokes
  to hand the conversational floor to the user. Mic re-arm is driven EXCLUSIVELY by this
  signal — the `endsWith("?")` heuristic and the 3-round cap are deleted.
- **In-activation context continuity**: within one assistant activation, the app carries
  the model's pending question, the id-tagged candidate options, and the user's verbatim
  replies into each follow-up turn's task-state block, so the model resolves natural
  replies itself. Cross-activation statelessness (voice sessions) is unchanged.
- **Restore the model's id map**: tool results and task-state blocks render
  `[c3] Mom — ending in 95` again (model-readable only); speech-facing text stays ID-free.
- **Spec alignment**: correct `action-confirmation` (masked suffixes replace full phone
  numbers; the existing "no keyword matcher" rule is preserved and strengthened), and add
  main-spec requirements for mic re-arm intent, in-activation continuity, and the
  model-readable vs. speech-facing text boundary.

## Impact

- **Affected specs**: `voice-pipeline`, `conversation-manager`, `action-confirmation`
- **Affected code**: `AssistantSession` (follow-up loop), `ConversationSession` /
  `ConversationManager` (ask_user event, pending-state), tool-result rendering
- **Explicitly out of scope**: any app-side language interpretation; Silero VAD and
  barge-in (ROADMAP-parked); chat UI behavior beyond accepting the ask_user event.
