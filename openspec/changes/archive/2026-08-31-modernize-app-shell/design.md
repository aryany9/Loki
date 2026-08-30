# modernize-app-shell — Design

## Context

`MainActivity` uses a `when(currentScreen)` enum navigation (`AppScreen`: SETUP/CHAT/PERMISSIONS/MODEL_LIBRARY/AGENT_PLAYGROUND); theme is applied via `LokiTheme(themeMode from ThemeRepository)` but no screen lets the user change it. `ChatScreen`'s TopAppBar holds the title, `ModelStatusBadge`, and four `IconButton`s (playground, model library, permissions, new chat). Persistence from the previous change exposes `listConversations()` (sorted by `updatedAt` desc), `loadConversation`, `deleteConversation`, `newConversation` on `ChatViewModel`. Hilt is available for a SettingsViewModel.

## Goals / Non-Goals

**Goals:**
- Gemini-like drawer: new chat, live recents, all destinations.
- Real Settings screen with working theme switching.
- Top bar reduced to hamburger + title + model badge.
- No new dependencies; reuse store + repositories.

**Non-Goals:**
- Navigation-library migration (keep the `AppScreen` enum `when` — it's small and works; Compose Navigation is a possible future refactor, not needed for a drawer).
- Model-switcher dropdown, account UI, greeting/empty state (Phase 5), in-drawer rename UI (delete only; rename API stays available for later).

## Decisions

### D1: Drawer lives inside `ChatScreen`'s Scaffold layer, not MainActivity
`ChatScreen` wraps its existing `Scaffold` in `ModalNavigationDrawer` with `ModalDrawerSheet` content. Other destinations (Model Library, Playground, Settings, Permissions) keep their full-screen layouts with back buttons — Gemini also shows a full-screen settings page, not a drawer-wrapped one.
*Rationale:* minimal blast radius; MainActivity's when-block only gains a `SETTINGS` branch. Rejected: app-wide drawer scaffold in MainActivity (would re-wrap every screen and churn all screens' insets).

### D2: Recents state — lifted into ChatViewModel
`ChatViewModel` exposes `conversations: StateFlow<List<ConversationRecord>>` refreshed on: init (after load), `newConversation()`, `loadConversation(id)`, `deleteConversation(id)`, and after each persisted turn (`appendTurn` already updates `updatedAt` → re-sort). Drawer renders from this flow; tapping a recent calls `loadConversation` and closes the drawer.
*Rejected:* drawer-owning repository/loader — duplicate state paths; the ViewModel is already the conversation owner.

### D3: Settings = own screen + Hilt ViewModel, theme-first
`SettingsScreen` (in `core/ui`) with `SettingsViewModel` (Hilt-injected `ThemeRepository`, `ConversationManager` for current-model display): 
- Theme mode: three-option segmented selector → `themeRepository.setThemeMode` (existing DataStore flow; `MainActivity` already recomposes `LokiTheme` from it — no extra plumbing).
- Current model section (read-only name + state; retry button on error).
- Links: Model Library, Agent Playground, Permissions.
- App version from `BuildConfig`.
Agent generation parameters deliberately stay in Agent Playground (they're model-scoped and expert-facing).

### D4: Model badge → popover, not dropdown-switcher
The badge becomes clickable, opening an anchored info card: model display name, state (with loading spinner/error + retry), "Manage models" row → Model Library. *Rationale:* actual switching touches `applyAgentConfig` + engine re-init flows that live behind Model Library/Playground; a fake dropdown that just navigates would be misleading. A true switcher is deferred with a spec note.

### D5: Back-handling and drawer state
Drawer state via `rememberDrawerState`; `BackHandler(enabled = drawerState.isOpen)` closes the drawer before the predictive-back gesture exits. After selecting a destination the drawer closes before navigation triggers.

## Risks / Trade-offs

- [Drawer + edge-to-edge inset double-handling] → `ModalDrawerSheet` handles its own insets; only verify status-bar spacing on the sheet header.
- [Recents list grows unbounded in drawer] → cap display at last 20 with "older conversations remain stored" — file retention cap still deferred.
- [Enum navigation grows brittle] → acceptable; single `SETTINGS` addition now, Navigation-library migration noted as future work.
- [Theme change mid-session] → already reactive via DataStore flow; verify no Activity recreation flash (theme is Compose-level).

## Migration Plan

Additive UI: drawer wrapper + new screen + enum branch. No data changes. Rollback = revert; persisted conversations unaffected.

## Open Questions

None blocking. Model-switcher dropdown explicitly deferred (noted in spec as future).
