# fix-navigation-and-startup — Design

## Context

`MainActivity` uses `var currentScreen by remember { mutableStateOf<AppScreen?>(null) }` inside `setContent`, with a `when(currentScreen)` switching destinations. Each non-chat screen has an in-UI back arrow that restores a hardcoded target, but there is NO `BackHandler` and no back stack — the system back button is handled by the OS, which finishes the Activity. Runtime readiness (LLM/ASR loaded) gates SETUP→CHAT. `ChatViewModel.loadInitialConversation()` calls `conversationManager.createConversation()` at startup when no conversation exists (line 112), so a conversation is created before any user action. Manifest `allowBackup="true"` means reinstall restores conversation files via Android backup.

## Goals / Non-Goals

**Goals:** system back walks the destination stack; chat is the sole root and back-from-chat exits; launch always lands on new-chat home; no conversation until first message; restored conversations listed in recents but not auto-opened.

**Non-Goals:** migrating to Compose Navigation (the enum + stack is sufficient and lower-risk); conversation rename/retention/model-switcher (separate backlog); changing screen transitions (already done).

## Decisions

### D1: Minimal in-memory back stack in MainActivity
Replace the single `currentScreen` `remember` value with a small `backStackState: MutableStateFlow<List<AppScreen>>` (or a `remember { mutableStateListOf }`) whose head is the current destination. Navigation helpers: `navigateTo(screen)` pushes; `goBack()` pops (no-op at root for chat). Wire a `BackHandler { if (!goBackRoot()) ... }` that pops when the stack has >1 entries, else finishes the Activity (action on root). Keep the SETUP↔CHAT readiness gate mapping into the same stack.
*Rationale:* minimal, testable, keeps the existing screens untouched. Rejected: Compose Navigation lib (new deps + API churn for a 5-destination skeleton).

### D2: Launch always on chat home, never auto-open a stored conversation
`loadInitialConversation()` becomes: set messages empty (home state) and DO NOT load or create any conversation. The active conversation id is `null` until the first message. Recents flow (`conversations` StateFlow) is still populated from `listConversations()` so the drawer shows prior chats. Selecting one from the drawer calls `selectConversation(id)` as today.
*Rationale:* matches "new chat by default, recents via drawer" and removes the eager-create bug.

### D3: Lazy creation on first message
In `sendMessage`/`executeChatTurn`, if `currentConversationId == null`, call `conversationManager.createConversation()` (persisted immediately) and use that id for the turn. The in-flight UI session is a draft until then. `clearChat`/new-chat resets to the draft (id = null) but keeps recents intact. This satisfies "don't create any chat till user interacts on the very same page."

### D4: Reinstall/backup — prefer home; keep recents
Because `allowBackup="true"`, restored conversation files may exist after reinstall. D2 already prevents auto-opening them; recents listing will surface them for the user to pick. Do not change `allowBackup` in this change (it also restores settings/models which is desirable); the home-first behavior is sufficient. Document that "fresh chat" is now guaranteed on every launch.

### D5: Root back behavior
On chat with an empty conversation stack, `BackHandler` allows the default finish (with modern predictive-back). On any non-root screen, it pops. This preserves existing expected behavior (back from chat exits app) while fixing the broken non-root case.

## Risks / Trade-offs

- [Back stack diverges from readiness gate] → keep the gate mapping minimal: SETUP→CHAT replaces the stack's tail on readiness transitions; unit test the common sequences.
- [Lazy creation changes `updatedAt`/sorting assumptions] → only creation timing changes; existing write-through and sorting logic unchanged.
- [Backup restores old chats user may not recall] → acceptable; recents make them discoverable, Home stays primary.
- [Process-death restore of currentScreen] → rememberStateSaved optionally; a fresh launch landing on home is the desired behavior per D2, so no saved-instance restore for the stack is intentional.

## Migration Plan

Single additive change to MainActivity + ChatViewModel startup. No data migration. Rollback = revert.

## Open Questions

None blocking.