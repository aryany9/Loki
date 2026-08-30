# UI Modernization Roadmap

Target: a Gemini-style, Material 3 Expressive experience for Loki. Dynamic Color
(Gemini-like, always-on) was confirmed as the design direction; brand palette
remains only as API 29/30 fallback.

## Status

| Phase | Change | Status |
|---|---|---|
| 1 | Foundation (tokens, Dynamic Color, edge-to-edge) | ✅ Captured: `modernize-ui-foundation` — ready to apply |
| 2 | Conversation surface (bubble-less, markdown, streaming, tool cards) | ✅ Captured: `modernize-chat-surface` — ready to apply |
| 3 | Composer (floating pill, morphing send/mic/stop button) | ✅ Captured: `modernize-composer` — ready to apply (scope: composer only; top-bar simplification folded into later shell work) |
| 4 | App shell (nav drawer, settings screen, top bar simplification) | ✅ Captured: `modernize-app-shell` — ready to apply (model-switcher dropdown deferred; badge → info popover) |
| 5 | Home state + motion (greeting, suggestion chips, transitions) | ✅ Captured: `modernize-home-motion` — ready to apply (final phase; includes vector-icon sweep + token sweep) |

Notes: Phases 2–3 and 4 may swap order (mostly independent), but all require
Phase 1. Phase 4's prerequisite — persistent conversation history — is now
captured as `persist-conversation-history` (file-backed JSON store via
kotlinx-serialization; no Room) and must land before the app-shell drawer.

## Phase details

### Phase 2 — Conversation surface
- Full-width assistant messages (no bubbles) — requires a MODIFIED delta on `chat-ui`
- Markdown + code block rendering (new dependency: Compose markdown renderer)
- Streaming token-by-token rendering with animated "thinking" state
- Tool execution rendered as expandable cards (replaces "✓ Action executed" pill)

### Phase 3 — Composer
- Borderless floating pill input bar
- Morphing send/mic/stop button based on state (ChatGPT behavior)
- Scroll-to-bottom FAB visible only when scrolled up

### Phase 4 — App shell
- `ModalNavigationDrawer`: New chat, recents, theme selector, links to
  Model Library / Agent Playground / Settings
- New Settings screen (first spec'd settings destination; hosts ThemeMode)
- Top bar simplified to Gemini-style: title + model selector dropdown left,
  avatar/menu right; ModelStatusBadge moves into dropdown/drawer

### Phase 5 — Home state + motion
- Empty-state greeting ("Hi {name} ✨") with suggestion chips
- Spring-based screen transitions, predictive back, haptics
- Remaining screens (Setup, Permissions, Model Library, Playground) retokenized

## Decisions so far

- Dynamic Color always-on, no opt-out toggle (brand identity not a priority)
- Foundation first so new shell components are correct from day one
- Conversation persistence is its own change, not folded into Phase 4
