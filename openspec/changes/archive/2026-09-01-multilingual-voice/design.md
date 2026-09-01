# Design: multilingual-voice

## Context

Three pinched points, all verified: (1) `AndroidTtsEngine.init` sets `tts?.language = Locale.US` and nothing ever calls `setLanguage` again; (2) the JNI bridge (`loki_whisper_bridge.cpp:50-81`) already accepts `language_j` with a silent `"en"` default, but the Kotlin API (`SttEngine.transcribeAudio(pcmAudio): String`) has no language parameter, so Loki can never pass anything; (3) `AgentConfig` (`ModelTypes.kt:137`, `@Serializable`, DataStore-persisted) carries system instruction + generation + runtime config but no language, and `buildSystemPrompt` never mentions language. The voice stack already runs whisper's multilingual tokenizer (`multilingual.tiktoken` asset). Product decisions locked: assistant-language only (no UI i18n); quality ceiling = whatever the loaded model + installed TTS voices support.

## Goals / Non-Goals

- **Goals**: one user-facing language setting; STT transcribes in the chosen language (or auto-detects); TTS speaks it; the LLM is instructed to respond in it — chat and voice, both strategies.
- **Non-Goals**: translating the app UI (string resources stay English); on-device translation of arbitrary text; per-conversation language switching mid-stream (setting applies from next conversation start, consistent with memory/prompt behavior); teaching the model languages it doesn't know.

## Decisions

### D1: One setting, `conversationLanguage: String = "auto"`, on AgentConfig
Values: `"auto"` or a BCP-47 tag. Rationale for living on `AgentConfig`: it already flows through `AgentConfigRepository` → `ConversationManager.setAgentConfig` → every new session with zero new wiring, and serialization-with-defaults makes old persisted configs deserialize unchanged. `"auto"` semantics: whisper auto-detects the spoken language; TTS uses the device default locale; the LLM is instructed to mirror the user's language.
*Alternative rejected:* a separate DataStore preference — duplicates the existing config path and would need its own propagation plumbing.

### D2: STT — thread the parameter to the bridge that already has the hole
`SttEngine.transcribeAudio(pcmAudio, language: String = "auto")` → `LiteRtWhisperEngine` passes it to the existing JNI `language_j` param. `"auto"` maps to whisper's auto-detect; explicit tags pass through. The bridge's `"en"` fallback becomes unreachable from Loki. Both STT call sites pass the setting: chat's `startSttVoiceInput` path and `AssistantSession`'s `STT_TRANSCRIBE` strategy.
*Alternative rejected:* whisper "language auto-detect per utterance always" without a setting — surprises users who want to lock a language (auto-detect can misfire on accented short utterances).

### D3: TTS — configure, don't construct-guess
`AndroidTtsEngine.configureLanguage(bcp47Tag: String?)`: `null`/`"auto"` → `Locale.getDefault()`; else `Locale.forLanguageTag(tag)`, applied via `tts.language` (with a `Log.w` + no-crash when the TTS engine reports the locale unavailable). Called at init (replacing the `Locale.US` hardcode) and from the config-apply path (`ConversationManager.applyAgentConfig` / `setAgentConfig`) so a Settings change re-speaks in the new language without engine rebuild. Per-utterance quality depends on installed system voices — surfaced in manual checks, not code.

### D4: Prompt directive — mirror for auto, lock for explicit
`buildSystemPrompt` appends exactly one language line: `"auto"` → *"Always respond in the same language the user writes or speaks in."*; explicit → *"Always respond in <LanguageName>."* (display name, e.g. "Hindi"). For DIRECT_AUDIO models this is the sole language signal (the model hears audio natively); for text/STT paths it reinforces the language of the transcribed input. Placement: after the custom instruction, before tool signatures — low-priority position so it never displaces safety/tool rules.

### D5: Settings picker — fixed list, Auto first
A "Conversation language" row in Settings: `Auto` + a fixed list of ~12 common languages (English, Hindi, Spanish, French, German, Portuguese, Italian, Chinese, Japanese, Korean, Arabic, Russian) mapped to BCP-47 tags. Fixed list keeps the UI simple and honest; the tag is free-form in the data model so more can be added without migration. Persisted through `AgentConfigRepository`; `AgentPlaygroundScreen`'s config editor also gains the field (it edits the same `AgentConfig`).

## Risks / Trade-offs

- **Auto-detect misfires on short accented utterances** → mitigated by the explicit-language option; whisper auto-detect is reliable once the user has spoken a full phrase.
- **TTS voice not installed** for a chosen language → Android TTS falls back to its default voice (often English-accented); logged, surfaced in manual checks; no code path can "fix" a missing system voice.
- **LLM multilingual ceiling** → the loaded model determines response quality; the directive instructs but cannot create ability. Documented in the proposal as the honest ceiling.
- **Backward compat** → `@Serializable` + default handles pre-change persisted configs; no migration.

## Migration Plan

One defaulted serialized field; engines gain optional-arg methods; call sites updated at the two STT transcribe paths and the TTS init/config-apply points. No persisted-state migration, no tool or confirmation-gate changes.

## Open Questions

- None. Languages beyond the fixed picker list remain addable by editing the list (data model is free-form).