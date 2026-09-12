## Why

Loki is built as a multilingual on-device AI assistant capable of understanding and generating speech in dozens of languages (via multilingual Whisper, Gemma on NPU, and adaptive Android TTS). However, the assistant's voice frequently switches to hardcoded English strings whenever Kotlin intercepts a turn:
- Fast-path tool executions in `ConversationSession` immediately return English strings (e.g. *"The time is 8:20 AM"*, *"Battery is at 85%"*, *"Opening YouTube"*).
- Tool execution errors or missing items in `formatErrorResponse` return static English strings (e.g. *"I couldn't find the requested item."*).
- Lock-screen access denials return static English guidance (*"Please unlock your phone to open YouTube."*).
- Confirmation repeat-back prompts (`pendingVoiceConfirmation.repeatBack`) fall back to hardcoded English strings on retry or silence timeout.
- System permission rationales speak hardcoded English strings (*"To do that, I need the Phone permission."*).

When a user speaks in Hindi, Spanish, French, German, or any other language, receiving an English voice response breaks the conversational experience. Because the on-device SLM on NPU generates tokens at high speed (~50 tokens/sec on Snapdragon 8 Elite), running an LLM generation pass over structured tool results, errors, and policy observations takes only ~200–300ms, enabling completely dynamic, natural multilingual speech without maintaining brittle, incomplete translation dictionaries.

## What Changes

- **Retire `formatFastPathResponse` and English `formatErrorResponse` in `ConversationSession`**: Remove hardcoded English strings for tools and tool errors (like item not found). Feed structured tool results and error observations back to the LLM so it generates the response in the user's spoken language.
- **Synthesize Access Denials and Permission Rationales via LLM**: Instead of breaking out of the loop with static English messages when `AccessDenied` or `PermissionRequired` occurs, feed the structured observation into the LLM prompt. The LLM generates natural guidance in the user's spoken language (and in `AssistantSession`, localized rationale is spoken before launching permission flows).
- **Enforce Response-Only Grammar on Terminal Tool & Denial Iterations**: Constrain post-terminal-tool, post-permission, and post-denial generation passes using response-only grammar (`{"response": "..."}`), supporting both LiteRT regex patterns and GBNF engines, preventing infinite tool loops while preserving multi-step advancing tools (e.g. `lookup_contact`).
- **Preserve Localized Confirmation Repeat-Back**: Store the LLM's generated confirmation question in `pendingVoiceConfirmation.repeatBack` so that silence timeouts and repeat prompts in `AssistantSession` speak the user's language rather than a hardcoded English fallback.
- **Multilingual TTS Script & Locale Adaptation**: Enhance `AndroidTtsEngine` script and locale switching to support major Unicode script families (Devanagari, Arabic, Cyrillic, CJK) and Latin-language diacritics/explicit locale mapping so utterances speak with proper phonetics and voice locales.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `tool-registry`: Modify turn handling on `AccessDenied`, `PermissionRequired`, and tool errors to pass observations to the conversational LLM loop under a response-only grammar for language-matched synthesis rather than terminating immediately with hardcoded English fallbacks.
- `multilingual-voice`: Specify that tool results, tool errors, permission rationales, voice confirmation repeat-backs, and policy denials are synthesized by the LLM in the user's active conversation language rather than intercepted by hardcoded application strings.

## Impact

- `core:conversation`: Removes `formatFastPathResponse` and hardcoded English in `formatErrorResponse`; updates `ConversationSession` to feed access denials, permissions, and tool execution results into the model's generation turn; stores localized `repeatBack` in `pendingVoiceConfirmation`; adds response-only grammar regex support for LiteRT.
- `core:assistant`: Aligns `AssistantSession` permission rationale handling to use localized synthesis rather than hardcoded English strings.
- `core:voice`: Improves `AndroidTtsEngine` locale resolution for multilingual utterances.
- User Experience: If the user speaks Hindi, Loki responds in Hindi. If the user speaks Spanish, Loki responds in Spanish. The assistant never inappropriately reverts to hardcoded English.
