## 1. Conversation Loop Updates

- [x] 1.1 Remove `formatFastPathResponse` and hardcoded English error handling in `formatErrorResponse` from `ConversationSession`
- [x] 1.2 Update `AccessDenied`, `PermissionRequired`, and tool error handling in `ConversationSession` to feed observations to the LLM prompt for language-matched synthesis
- [x] 1.3 Add LiteRT-compatible regex response-only constraint in `GrammarBuilder` and apply response-only constraint to terminal tool executions, permissions, and denials while preserving advancing tool grammar (`lookup_contact`)
- [x] 1.4 Update `pendingVoiceConfirmation.repeatBack` with the LLM's generated question text (`sanitized`) in both `ask_user` and `DirectResponse` so voice retries preserve language
- [x] 1.5 Add fallback handling in `ConversationSession` returning `decision.message` / error message if post-tool / post-denial LLM generation fails instead of terminating channel
- [x] 1.6 Update `AssistantSession` permission rationale handling to allow spoken localized response before navigating to the permissions screen

## 2. TTS Engine Enhancements

- [x] 2.1 Ensure `AndroidTtsEngine` dynamically configures TTS language locales matching utterance text script (Devanagari, Arabic, Cyrillic, CJK, Indic) and Latin diacritics in `auto` mode

## 3. Unit & Integration Testing

- [x] 3.1 Update unit tests in `ConversationSessionTest` verifying LLM generation following terminal tool results, tool errors, access denials, permissions, and localized `repeatBack`
- [x] 3.2 Update unit tests in `AssistantSessionTest` for multi-turn tool synthesis flows
- [x] 3.3 Execute unit test suites (`:core:conversation:test`, `:core:tools:test`, `:core:assistant:test`)

## 4. Verification & Build

- [x] 4.1 Build debug APK and verify compilation
- [x] 4.2 Install APK on connected device for real-world verification

