## 1. AgentConfig field

- [x] 1.1 Add `conversationLanguage: String = "auto"` to `AgentConfig` (`ModelTypes.kt`, `@Serializable` — default keeps old persisted JSON deserializing); unit test round-trip + pre-change JSON (missing field) deserialization

## 2. STT language threading

- [x] 2.1 `SttEngine.transcribeAudio(pcmAudio, language: String = "auto")`; `LiteRtWhisperEngine` passes it to the existing JNI `language_j` param (bridge unchanged — it already accepts it)
- [x] 2.2 Both STT call sites pass the configured language: chat `startSttVoiceInput` path and `AssistantSession` STT_TRANSCRIBE strategy (from `conversationManager.activeAgentConfig.conversationLanguage`)
- [x] 2.3 Unit test: language param reaches the engine (fake STT capturing the arg); default "auto" when unset

## 3. TTS locale

- [x] 3.1 `AndroidTtsEngine`: replace `tts?.language = Locale.US` with `configureLanguage(bcp47Tag: String?)` — null/"auto" → `Locale.getDefault()`, else `Locale.forLanguageTag`; apply at init and on agent-config apply (`ConversationManager.setAgentConfig`/`applyAgentConfig` re-configures the engine); log+no-crash when locale unavailable
- [x] 3.2 Unit test: configureLanguage resolves tags and "auto"/null to default; init without config uses device default (no Locale.US)

## 4. Prompt directive

- [x] 4.1 `ConversationSession.buildSystemPrompt`: append the language directive — "auto" → respond in the user's language; explicit → "Always respond in <LanguageName>." — placed after custom instructions, before tool signatures; derive display name from the BCP-47 tag
- [x] 4.2 Unit test: directive present for "auto" and explicit tags; absent/correct form with default config; directive after custom instruction block

## 5. Settings + Playground UI

- [x] 5.1 SettingsScreen: "Conversation language" row — Auto + fixed list (en, hi, es, fr, de, pt, it, zh, ja, ko, ar, ru); persists via AgentConfigRepository; theme tokens only
- [x] 5.2 AgentPlaygroundScreen config editor exposes the same field; both write through `AgentConfigRepository`
- [x] 5.3 Unit test: SettingsViewModel/AgentPlaygroundViewModel update + persist `conversationLanguage`

## 6. Validation

- [x] 6.1 `./gradlew test :app:assembleDebug` passes; all existing suites green
- [x] 6.2 Manual device matrix: set Hindi → speak in Hindi (STT transcribes Hindi; TTS replies in Hindi if a Hindi voice is installed); Spanish text chat → Spanish replies (auto mode); English explicit → English replies regardless of input language; DIRECT_AUDIO model responds in the spoken language; missing TTS voice degrades gracefully
- [x] 6.3 `openspec validate multilingual-voice` passes; tick all tasks