# Proposal: multilingual-voice

## Why

Loki is hardcoded English end-to-end: `AndroidTtsEngine` pins `tts.language = Locale.US` at init, the Kotlin STT API never passes a language to the whisper bridge (which itself defaults to `"en"` natively despite accepting one), and the system prompt never instructs the model about language. Yet the hard parts already exist offline — whisper is the *multilingual* variant (`multilingual.tiktoken` ships in assets) and its JNI bridge accepts a `language` parameter. Users speaking Hindi, Spanish, or anything else get an assistant that mis-transcribes and answers in English. This change makes the assistant converse in the user's language — voice in, voice out, and text — with zero new dependencies and no UI-string translation (explicitly out of scope per the product decision).

## What Changes

- Add `conversationLanguage: String = "auto"` to `AgentConfig` (`@Serializable`, defaulted — existing persisted configs deserialize unchanged). Values: `"auto"` or a BCP-47 tag (`"en"`, `"hi"`, `"es"`, …).
- **STT**: thread `language` through `SttEngine.transcribeAudio` → `LiteRtWhisperEngine` → the existing JNI bridge param (`"auto"` triggers whisper auto-detect; the bridge's silent `"en"` default stops being reachable from Loki code).
- **TTS**: `AndroidTtsEngine` gains `configureLanguage(bcp47Tag)` — replacing the hardcoded `Locale.US` with `Locale.forLanguageTag`; `"auto"` falls back to the device default locale; re-applied when the agent config changes.
- **Prompt**: `buildSystemPrompt` appends a language directive — `"auto"` → *"Respond in the same language the user writes or speaks in."*; explicit tag → *"Always respond in <language>."* For audio-capable models (DIRECT_AUDIO), this is the only language signal needed — the model hears the audio natively.
- **Settings**: a "Conversation language" picker (Auto + a fixed list of common languages) in the Settings screen, persisted via the existing `AgentConfigRepository` → `ConversationManager.setAgentConfig` path.
- Honest capability ceiling documented: transcription quality depends on the bundled multilingual whisper; TTS quality on installed system TTS voices; response language on the loaded LLM's own multilingual ability.

## Capabilities

### New Capabilities
- `multilingual-voice`: The conversation-language setting and its propagation to STT, TTS, and the response-language prompt directive.

### Modified Capabilities
- `voice-pipeline`: TTS no longer pins `Locale.US`; STT transcription accepts a language parameter reaching the whisper bridge.
- `conversation-manager`: system prompt carries the response-language directive derived from `AgentConfig.conversationLanguage`.
- `app-shell`: Settings screen gains the conversation-language picker.

## Impact

- **`core/models`**: one defaulted `@Serializable` field on `AgentConfig` (backward-compatible with persisted JSON).
- **`core/voice/tts`**: `configureLanguage` + locale resolution; **`core/voice/stt`**: language param threading (JNI bridge unchanged — it already takes the param).
- **`core/conversation`**: prompt directive in `buildSystemPrompt`; language plumbed to STT call sites; TTS reconfiguration on `applyAgentConfig`.
- **`core/ui`**: `AgentPlaygroundViewModel`/`SettingsViewModel` surface + `SettingsScreen` picker.
- No new dependencies, no manifest changes, no tool changes.