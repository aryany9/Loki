# fix-navigation-and-startup — Tasks

## fix-navigation-and-startup

- [ ] **T1 — MainActivity back stack (D1)**
  - Replace `var currentScreen` with a `remember { mutableStateListOf(AppScreen.CHAT) }` back stack (head = current).
  - Add `navigateTo(screen)`: push onto stack (de-dupe same entry if top).
  - Add `goBack(): Boolean`: pop if size > 1; return false if at root (chat).
  - Add `BackHandler(enabled = currentScreen != AppScreen.CHAT) { goBack() }` so back walks the stack and only finishes on chat root. Keep drawer-close-back precedence.
  - Map existing `onNavigate` / `onNavigateBack` callbacks to the new stack ops (SETTINGS, MODEL_LIBRARY, AGENT_PLAYGROUND, PERMISSIONS).
  - **Verification:** unit-test stack ops; manual: back from each screen returns to chat, back from chat exits.

- [ ] **T2 — Lazy home-on-launch (D2, D3 — ChatViewModel)**
  - In `ChatViewModel`, replace `loadInitialConversation()` eager `createConversation()` with: `currentConversationId = null`, empty messages, home state shown. Still populate `conversations` recents flow from `listConversations()`.
  - In `executeChatTurn()` (first send): if `currentConversationId == null`, `conversationManager.createConversation()` → assign id → persist turn.
  - Ensure `clearChat()`/`newChat()` resets to id=`null` (draft) without creating a record.
  - **Verification:** fresh install shows home + no conversation row in store; restart shows home (not last chat); recents still listed; first message creates+appends.

- [ ] **T3 — Backup/reinstall sanity (D4)**
  - Keep `allowBackup="true"`. Confirm reinstalled/restored app opens on home + restored conversations appear in drawer recents but are NOT auto-opened. (No manifest change; behavior comes from T1/T2.)
  - **Verification:** install → send a message → uninstall → reinstall → confirm home state + drawer shows prior conversation to pick.

- [ ] **T4 — Back-from-settings & nested nav (D5)**
  - Wire Settings drawer item to push Settings onto the stack (so back pops to chat) instead of jumping to CHAT via arrow target.
  - Wire the in-UI back arrow on Settings/ModelLibrary/etc. to `goBack()` consistently.
  - **Verification:** back from Settings → chat; chat → Playground → Library → back → Playground → back → chat.

## voice-start-cue

- [ ] **T1 — Tone source (D1)**
  - Add `core/sound` or generate a short Gemini-like attention tone (rising "ding" ~250ms, non-TTS, no words). Provide as a bundled `res/raw/` asset OR synthesize at runtime via `AudioTrack` to avoid a new binary asset dependency. Decide in review.
  - **Verification:** tone audible, short, non-speech; plays regardless of model type.

- [ ] **T2 — Trigger on voice start (D2)**
  - Play the cue at the start of voice input for BOTH chat mic (in ChatViewModel/composer) and assistant long-press (LokiVoiceInteractionSession), before recording/listening state begins.
  - **Verification:** tap mic → hear cue before recording starts; long-press assistant → cue on start, no cue on stop.

- [ ] **T3 — No regressions**
  - Ensure cue does not play on generation stop / completion; ensure volume respects media stream and is muted by device DND appropriately (use `STREAM_MUSIC` with `AudioManager` flag or `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY` as appropriate — decide in review).
  - **Verification:** no cue on stop; respects DND; not doubled on config change.
