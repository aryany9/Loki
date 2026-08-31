# fix-startup-navigation-voice — Tasks

## Startup & navigation (Bugs 1 + 3)

- [ ] **T1 — MainActivity back stack (D1, D5)**
  - Replace `var currentScreen` with `remember { mutableStateListOf(AppScreen.CHAT) }` back stack (head = current).
  - `navigateTo(screen)`: push; de-dupe if top. `goBack(): Boolean`: pop if size>1, else false.
  - `BackHandler(enabled = currentScreen != AppScreen.CHAT) { goBack() }`; keep drawer-close precedence.
  - Map existing `onNavigate`/`onNavigateBack` callbacks; wire Settings drawer item to push (so back pops to chat).
  - **Verification:** unit-test stack ops; manual: back from each screen → chat; nested chat→Playground→Library→back×2 → chat; back from chat exits.

- [ ] **T2 — Lazy home-on-launch (D2)**
  - `ChatViewModel.loadInitialConversation()` → empty messages + `currentConversationId = null`; DO NOT `createConversation()`; keep populating `conversations` recents from `listConversations()`.
  - **Verification:** install → home empty; restart → home (NOT last conversation); recents still listed.

- [ ] **T3 — Lazy create on first message (D3)**
  - In `executeChatTurn()`: if `currentConversationId == null` → `createConversation()` + assign + persist turn. `clearChat()`/`newChat()` resets id to null (no create).
  - **Verification:** fresh install → send first message → a single conversation row appears in store.

- [ ] **T4 — Backup/reinstall sanity (D4)**
  - Keep `allowBackup="true"`; confirm reinstall opens home and restored chats appear in drawer recents (not auto-opened).
  - **Verification:** install→message→uninstall→reinstall → home + prior conversation in recents.

## Voice start cue (Bug 2)

- [ ] **T5 — Audio cue helper (D6)**
  - New `core/sound/AudioCue.kt`: `playStartTone()` synthesizes ~250 ms 440→880 Hz glide via `AudioTrack` (STREAM_MUSIC) on bg thread; `AudioAttributes` (`USAGE_ASSISTANCE_ACCESSIBILITY`, `CONTENT_TYPE_SONIFICATION`); native sample-rate helper + 44100 fallback; log-on-failure, never block.
  - **Verification:** local unit test: single playback per call; idempotent in a session.

- [ ] **T6 — Trigger from chat mic (D8, D7)**
  - In `ChatViewModel.startVoiceInput()`, call `AudioCue.playStartTone()` before recorder/STT opens; reset guard in `stopVoiceInput()`.
  - **Verification:** mic press → tone before recording; stop → no tone; no doubles on rotation.

- [ ] **T7 — Trigger from assistant long-press (D8, D7)**
  - In `LokiVoiceInteractionSession` voice-start path, call `AudioCue.playStartTone()` at long-press threshold before listening overlay; reset guard on voice end.
  - **Verification:** long-press → tone; start→stop cycle → no extra tone; survives config change.

- [ ] **T8 — Flag kill-switch (D6/D7)**
  - Add hidden `audioStartCueEnabled=true` flag (default true) gating `playStartTone`; keep in `core/sound`.
  - **Verification:** flag false → no tone on either entry point.

## Validation

- [ ] `openspec capture --check fix-startup-navigation-voice` passes (canonical spec deltas apply).
- [ ] Spec-library diff review for app-shell / conversation-persistence / voice-interaction-ui / chat-ui.