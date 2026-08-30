# modernize-home-motion — Design

## Context

`ChatViewModel` seeds a fake assistant "Hi, I'm Loki..." message on new conversations, so no true empty state exists. ~20 unicode glyphs serve as icons across Chat/ModelLibrary/Settings/AgentPlayground/Permissions. `MainActivity` switches screens in a `when` block with no animation. 9 hardcoded `fontSize = N.sp` remain in Setup/Permissions/AgentPlayground. Phase 1 tokens and Phase 4's drawer/settings are in place.

## Goals / Non-Goals

**Goals:**
- True empty-state home: greeting + suggestion chips (Gemini-style).
- All glyphs → Material vector icons.
- Animated screen transitions + haptics.
- Zero hardcoded font sizes/corners in any screen.

**Non-Goals:**
- Hilt-ViewModel migration, retention cap, rename UI, model-switcher dropdown.
- Speech: greeting text is static/personalized-lite (no account name — there is no account system).

## Decisions

### D1: Empty state = `turns.isEmpty()`, not a seeded message
Remove both seeded greeting messages from `ChatViewModel` (init + `newConversation`). The home state renders when the conversation has zero UI messages. `mapTurnsToMessages(emptyList())` yields empty list → home composable shows.
*Rationale:* seeding a fake assistant message pollutes persistence and streaming logic (a stored conversation with only the greeting would render it forever).

### D2: Greeting + chips — composable in ChatScreen, suggestions static
Home composable: large greeting (`LokiTypography.displaySmall`-scale, "Hi there ✨") top-left-aligned like Gemini, below it 3–4 suggestion chips ("What can you do?", "Set an alarm", "Tell me a joke", "Summarize my last chat"). Tapping a chip calls `sendMessage(chipText)` directly (simpler and immediate) with light haptic. Suggestions are a static list in `core/ui` — no LLM/personalization.
*Rejected:* chips filling the composer only (extra tap); dynamic suggestions from the model (needs a generation round-trip).

### D3: Icons — add `material-icons-extended`
One catalog entry, resolved via the Compose BOM. Mapping: ☰→Icons.AutoMirrored.Filled.Menu (or Menu), ▶→Send, ■→Stop, 🎤→Mic, ➕→Add, ←→AutoMirrored ArrowBack, ✓→Check/CheckCircle, ✕/✗→Close/Cancel, ▲/▼→KeyboardArrowUp/Down, ⚠️→Warning. R8 keeps only used icons in release; note ~debug APK size increase.
*Rejected:* hand-drawn vectors (effort), staying with glyphs (fails the goal).

### D4: Screen transitions — `Crossfade` + slide in MainActivity
Wrap the `when(currentScreen)` content in `AnimatedContent` with fade+slight slide (forward on nav, reverse on back via a direction hint). Subtle: 200–250ms tween. Drawer item motion: default M3 ripple/pressed states only — no custom animation (already smooth).
*Rejected:* full shared-element transitions (Compose support still maturing; overkill here).

### D5: Haptics — `LocalHapticFeedback`
`HapticFeedbackType.LongPress`-style light tick via `LocalHapticFeedback.performHapticFeedback` on: send tap, stop-generation tap, chip tap. Viewmodels stay haptic-free (UI concern).

## Risks / Trade-offs

- [icons-extended adds ~10MB debug APK] → R8/minify strips unused in release; verify release build size.
- [Home state must not appear over restored conversations] → home renders only when turns empty; restored conversations have turns (or are genuinely new/empty → home is correct).
- [AnimatedContent around screen switch re-composes both screens during transition] → short duration, screens are lightweight; acceptable.
- [Chips firing sendMessage directly may surprise users wanting to edit] → acceptable, matches Gemini behavior; composer prefill alternative rejected in D2.

## Migration Plan

Independent slices: icons sweep, home state, transitions, token sweep — each shippable. Rollback = revert; no data changes.

## Open Questions

None blocking.
