# fix-startup-navigation-voice — Design

## Context

`MainActivity` uses `var currentScreen by remember { mutableStateOf<AppScreen?>(null) }` inside `setContent`, with a `when(currentScreen)` switching destinations. Non-chat screens only expose an in-UI back arrow that restores a hardcoded target; there is **no `BackHandler`** and **no back stack** — the system back button finishes the Activity anywhere. Runtime readiness (LLM/ASR loaded) gates SETUP→CHAT. `ChatViewModel.loadInitialConversation()` calls `conversationManager.createConversation()` at startup when no conversation exists (eager creation). Manifest `allowBackup="true"` restores conversation files on reinstall → the previous chat reopens instead of a fresh home. Voice start (chat mic `ChatViewModel.startVoiceInput()` / `ChatScreen` mic `onClick`, and assistant long-press via `LokiVoiceInteractionSession`) currently has only visual feedback.

## Goals / Non-Goals

**Goals:** system back walks the destination stack (chat is root, back-from-chat exits); launch always lands on new-chat empty home; no conversation created until the first message; restored conversations reachable via drawer recents; a short non-TTS start tone plays on voice start on both entry points.

**Non-Goals:** Compose Navigation migration; conversation rename/retention; Hilt-ifying ViewModels; stop/success/error tones; user-customizable tone (deferred).

## Decisions

### D1: Minimal in-memory back stack in MainActivity (back nav)
Replace the single `currentScreen` `remember` value with a `remember { mutableStateListOf(AppScreen.CHAT) }` back stack (head = current destination). `navigateTo(screen)` pushes; `goBack()` pops (returns false at root). Wire a `BackHandler` that pops when stack size > 1, else finishes the Activity (chat root) — preserving existing back-from-chat-exits behavior. Keep the SETUP↔CHAT readiness gate mapping into the same stack (SETUP→CHAT replaces the tail on readiness).
*Rationale:* minimal/testable, keeps screens untouched.

### D2: Launch always on chat home, never auto-open stored convo (landing)
`loadInitialConversation()` → empty messages, `currentConversationId = null`, home state shown; **no** `createConversation()` at startup. Recents flow (`conversations` StateFlow) still populated from `listConversations()` so the drawer shows prior chats.
*Rationale:* removes eager-create bug + fixes reinstall-reopens-prior-chat.

### D3: Lazy creation on first message (persistence)
In `executeChatTurn()`, if `currentConversationId == null`, `conversationManager.createConversation()` → assign + persist. In-flight session is a draft until then. `clearChat()`/`newChat()` resets to id=`null` (no create).
*Rationale:* "don't create any chat till user interacts on the very same page."

### D4: Reinstall/backup — prefer home; keep recents
Keep `allowBackup="true"`. Restored conversation files after reinstall are surfaced only in drawer recents, never auto-opened (D2). Document that "fresh chat" is now guaranteed on every launch.
*Rationale:* backup still restores settings/models (desirable); home-first behavior suffices.

### D5: Root back behavior
Chat root → back finishes (predictive-back OK). Non-root → pop. Preserves existing root-exit expectation while fixing broken non-root case.

### D6: Runtime-synthesized start tone (audio cue)
Tiny `core/sound/AudioCue.kt` synthesizes a ~250 ms 440→880 Hz frequency glide (fast attack, short release) via `AudioTrack` (STREAM_MUSIC) on a bg thread; 440→880 Hz glide, no asset. Use `AudioTrack.getNativeOutputSampleRate` with 44100 fallback. `AudioAttributes` = `USAGE_ASSISTANCE_ACCESSIBILITY` + `CONTENT_TYPE_SONIFICATION`.
*Rationale:* asset-free, themeable, no binary. Rejected bundled WAV (asset + AssetFileDescriptor lifecycle).

### D7: Idempotent trigger guard
Per-session `voiceStartCuePlayed` boolean; reset false on `startVoiceSession()`; play only if false; set true after. Reset false on voice stop. Prevents doubles on config change / recomposition restart.

### D8: Trigger placement
- Chat mic: in `ChatViewModel.startVoiceInput()`, play cue **before** the recorder/STT path opens. Reset guard on `stopVoiceInput()`.
- Assistant long-press: in `LokiVoiceInteractionSession` voice-start (long-press threshold), play cue **before** listening overlay animates. Reset guard on voice end.

## Risks / Trade-offs

- Back stack vs. readiness gate → keep mapping minimal; unit-test common sequences.
- Lazy creation vs. `updatedAt`/sorting → only creation timing changes.
- Backup restores old chats user may not recall → acceptable; recents discoverable.
- Tone double-play on config change → D7 guard.
- Tone on stop instead of start → trigger only in start path; test call sites.
- AudioTrack underrun / wrong sample rate → native rate helper + 44100 fallback; log on failure, never block input.
- Hidden `audioStartCueEnabled` flag = kill switch + accessibility escape hatch; future user-customization is a separate backlog item.

## Migration Plan

Additive: new `core/sound` module + 1 task in ChatViewModel + 1 in LokiVoiceInteractionSession + MainActivity back-stack refactor + ChatViewModel startup tweak. No data/state migration. Rollback = feature flag / revert.

## Open Questions

None blocking.