## Context

Loki was designed as a multilingual on-device assistant. Its direct-audio SLM (Gemma on Snapdragon 8 Elite NPU) and Whisper STT can comprehend user queries in any language. However, several Kotlin execution pathways currently intercept the interaction and speak hardcoded English strings:
1. `formatFastPathResponse` in `ConversationSession`: Formats hardcoded English strings like `"The time is 8:20 AM"` or `"Battery is at 85%"`.
2. `formatErrorResponse` in `ConversationSession`: Speaks static English like `"I couldn't find the requested item."` when tools encounter `ToolErrorCode.NOT_FOUND` or errors.
3. `AccessDecision.Deny`: Breaks the execution loop and speaks hardcoded English strings like `"Please unlock your phone to open YouTube."`.
4. `ToolExecutionResult.PermissionRequired`: Speaks hardcoded English strings in `ConversationSession` and `AssistantSession` (`"To do that, I need the Phone permission."`).
5. Voice confirmation repeat-backs in `ConversationSession`: Overwrites the LLM's localized confirmation question with a static English `repeatBack = "Shall I call Rahul?"` on silence timeouts or retries.
6. `AndroidTtsEngine`: Only had an automatic script-detection switch for Devanagari (Hindi) when in `"auto"` language mode, falling back to English/device-default locale for all other scripts.

Because direct-audio NPU inference on the Snapdragon 8 Elite generates text tokens at ~50 tokens/sec, running a 2nd text-only LLM generation pass over structured tool results, security observations, and permission rationales takes only ~200–300ms. By routing tool results, errors, access denials, and permission requests through the LLM under a response-only grammar rather than hardcoding static Kotlin text, Loki achieves natural, fluent multilingual speech across all supported languages.

## Goals / Non-Goals

**Goals:**
- Eliminate hardcoded English response strings across tool results, tool errors, permission rationales, voice confirmation repeat-backs, and policy denials.
- Feed structured tool results (`data`), error observations, permission requirements, and policy denials (`AccessDenied`) back to the LLM so the assistant responds in the language the user initiated the turn with.
- Constrain post-terminal-tool, post-permission, and post-denial turns using response-only grammar compatible with both LiteRT regex (`ResponseFormat.regex`) and GBNF engines, while preserving multi-step advancing tools (like `lookup_contact`).
- Store the LLM's generated confirmation question in `pendingVoiceConfirmation.repeatBack` so voice retries maintain the spoken language.
- Expand `AndroidTtsEngine` script and locale switching to support major language script families and Latin diacritics dynamically.

**Non-Goals:**
- Localizing developer/system debug logs (logs remain English for diagnostic consistency).
- Implementing static `strings.xml` resource bundles for 100+ languages (the LLM dynamically handles natural language generation).

## Decisions

### 1. ReAct Generation for Tool Results, Errors, Denials, and Permissions
- **Decision:** Remove `formatFastPathResponse` and hardcoded English `formatErrorResponse`. Route successful terminal tool executions, tool errors (e.g. `NOT_FOUND`), policy denials, and permission requirements through the LLM observation prompt.
- **Implementation:**
  - When a tool executes or is denied by `LockScreenActionMatrixPolicy` / `PermissionManager`, the structured result is emitted as a `ToolExecuted` event (for UI/logging) and appended as a `ConversationTurn.ToolExecutionResult`.
  - The observation is formatted as `currentTurnPrompt`:
    - For success: `"Tool result for $tool: $data"`
    - For denial: `"Tool result for $tool: Access denied: ${decision.reason}. Inform the user in their language that they must unlock their phone to proceed."`
    - For error / not found: `"Tool result for $tool: Error $errorCode: $message. Inform the user in their language."`
    - For permission required: `"Tool result for $tool: Permission required: ${execResult.permission}. Inform the user in their language that they need to grant this permission in settings to proceed."`
  - In `AssistantSession`, when `ToolErrorCode.PERMISSION_DENIED` is received, allow the conversational turn's localized response to be spoken before transitioning to `openPermissionsScreen()`.
- **Rationale:** The LLM intrinsically understands the user's language from conversational context. This delivers true multilingual support without requiring application-side translation matrices.

### 2. Turn Termination Guarantee & Engine Compatibility (LiteRT Regex + GBNF)
- **Decision:** Post-terminal-tool, post-error, post-permission, and post-denial iterations MUST be constrained to a response-only grammar (`{"response": "..."}`).
- **Engine Compatibility Implementation:**
  - LiteRT SDK on Android does not parse GBNF; it only supports regex constraints (`ResponseFormat.regex`). `LiteRtLlmEngine` explicitly drops grammars starting with `root ::=`.
  - `GrammarBuilder` will provide a response-only pattern format supported by LiteRT regex (`\s*\{\s*"response"\s*:\s*".*"\s*\}\s*`) alongside the GBNF fallback.
- **Scoping to Terminal Tools (Preserving Advancing Tools):**
  - Only **terminal tool results** (tools that previously used `formatFastPathResponse` or finished execution, e.g. `open_app`, `get_current_time`, `set_timer`, `call_contact`), errors, and denials enforce response-only grammar.
  - **Advancing/intermediate tools** (specifically `lookup_contact`, which advances contact resolution and expects subsequent tool calls like `ask_user` or `call_contact`) retain their scoped available tool grammar.

### 3. Confirmation Repeat-Back Localization
- **Decision:** When the LLM generates a confirmation question (e.g. via `ask_user`), update `pendingVoiceConfirmation.repeatBack` with the LLM's generated question text (`sanitized`) rather than keeping the English fallback.
- **Rationale:** If silence occurs or the user asks Loki to repeat the question, `AssistantSession` speaks the stored `repeatBack`. Keeping the LLM-generated string guarantees repeat prompts remain in the user's language.

### 4. Fallback Resilience on Generation Failure
- **Decision:** If the LLM throws an exception or fails generation during Iteration 2 (post-tool / post-denial synthesis), `ConversationSession` gracefully falls back to `decision.message ?: "Please unlock your device to proceed."` (for denials), the tool error message (for errors), or a default completion string rather than terminating the channel with an unhandled `ConversationEvent.Error`.
- **Rationale:** Guarantees the user receives an audible response even during low-memory or model timeout scenarios.

### 5. Expanded TTS Script Detection & Locale Switching
- **Decision:** Enhance `AndroidTtsEngine`'s script-detection heuristic in `auto` mode beyond Devanagari to support additional common non-Latin Unicode scripts:
  - Devanagari (`\u0900..\u097F`) → Hindi (`hi-IN`)
  - Arabic (`\u0600..\u06FF`) → Arabic (`ar`)
  - Cyrillic (`\u0400..\u04FF`) → Russian (`ru`)
  - CJK Unified Ideographs (`\u4E00..\u9FFF`) → Chinese/Japanese/Korean
  - Other Indic blocks (Tamil, Telugu, Bengali) → Corresponding Indic locales
- For Latin-based scripts (Spanish, French, German), check for distinctive accent characters (`¿`, `¡`, `ñ`, `é`, `ü`, `ß`, `ç`) or allow explicit selection via `conversationLanguage` in settings.

## Risks / Trade-offs

- **[Risk] Extra latency for tool turns (~250ms)**:
  → *Mitigation*: On Snapdragon 8 Elite NPU, generating 10–15 text tokens takes ~200–300ms. This trade-off is accepted by product design to achieve authentic multilingual capability.
- **[Risk] Existing Unit Test Suite Breakage**:
  → *Mitigation*: Multiple tests in both `ConversationSessionTest` and `AssistantSessionTest` stub single-turn LLM responses for tool calls and assert prompt counts (`engine.prompts.size == 1`). Both test suites will be systematically updated to expect the second synthesis turn.

