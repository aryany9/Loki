## Why

With Change 1 (`model-library`) and Change 2 (`engine-capabilities`) complete, Loki can manage multiple models and configure LLM runtime capabilities (`AgentConfig`, `GenerationConfig`, `RuntimeConfig`). However, users currently have no UI to customize the system prompt, tune a local model without the full Voice Assistant workflow, or test model behavior.

**Product goal.** Loki is a private, on-device AI assistant (chat + voice + tools). Chat, Voice, and Tools are the core product; the Model Library is a core supporting feature. The **Agent Playground** is an advanced / power-user feature layered on top. The normal Loki user must not be required to understand Temperature, Top-K, KV capacity, etc. The Playground exists so power users and developers can experiment with and tune a local model — it is not meant to turn Loki into an LLM configuration laboratory, nor to reproduce Google AI Edge Gallery as a feature checklist.

**Hard prerequisite.** Any generation parameter the Playground exposes must actually reach the inference engine. Merely persisting `AgentConfig` while inference ignores it is misleading and not acceptable. In the current `LiteRtLlmEngine`, `generationConfig` (temperature, topK, topP, seed) is not wired into the native conversation, and `generate()`'s `maxTokens` is not passed to `sendMessageAsync`. Both must be implemented for this change to be complete.

**Conversation-layer integration is required.** The Playground's System Prompt editor and generation config are only meaningful if they flow into actual chat/voice inference. Today `ConversationManager`/`ConversationSession` ignore `AgentConfig`: `ConversationSession.buildSystemPrompt()` hardcodes its own prompt and never reads `AgentConfig.systemInstruction`, and every `generate()` call uses the `maxTokens = 256` default. Wiring this change therefore requires threading `AgentConfig` through the conversation layer, not just the engine and UI.

## What Changes

- Add a **model-engine prerequisite**: wire `AgentConfig.generationConfig` into LiteRT-LM inference so temperature, topK, topP, and seed actually affect output (via `SamplerConfig` on the `ConversationConfig`), and pass `maxTokens` through `generate()` to `sendMessageAsync`. `runtimeConfig` (backend, KV capacity) already flows correctly; confirm and keep it.
- Add a dedicated **Model & Agent Configuration** screen (`AgentPlaygroundScreen`) accessible from navigation, built around **progressive disclosure**.
- **Model section**: selected model, loaded/not-loaded status, and basic capability flags (`ModelCapabilities`). No raw tuning controls here.
- **Agent section**: an editable **System Prompt** (`systemInstruction`) field — always visible, core to the assistant experience.
- **Response behavior section**: a simple high-level preset — **Fast / Balanced / Precise** — as a UX abstraction that maps to generation parameters. It is not a new inference capability. (Optional if it complicates the architecture.)
- **Test Prompt section**: prompt input, Run, and response output (with tool execution diagnostics) for live single-turn testing against the active model config.
- **Advanced section (collapsible)** — the only place raw technical controls appear:
  - **Temperature** (0.0–2.0 slider)
  - **Top-K** (1–100 slider)
  - **Top-P** (0.0–1.0 slider)
  - **Seed** (optional)
  - **Max Output Tokens** (optional)
  - **Execution Backend**: Automatic / GPU / CPU (unchanged contract)
  - **Context / KV capacity** — presented as a runtime setting (e.g. 1024–16384), *not* as an "intelligence" control.
- Provide `[ Reset Defaults ]`, `[ Save Configuration ]`, and the test prompt runner.
- Integrate the screen into `AppScreen` routing.

**Test Prompt semantics (resolved).** The Test Prompt runs a single-turn `ConversationSession.processUtterance()` (not a raw `llmEngine.generate()`), so it exercises the real agent path — system prompt, generation config, tool parsing, and diagnostics — against the active `AgentConfig`. This avoids a second, divergent inference code path and keeps tool execution observable in the playground.

**Capability source (resolved).** The Model section shows basic capabilities (text / tool / audio / vision). These come from the loaded engine's `ModelCapabilities` when a model is LOADED, falling back to the persisted `ModelRecord`/`ModelRecordCapabilities` (audio flag) when none is loaded. No new capability metadata is introduced in this change.

**Resolved gaps (for implementation).**
- **G1 — Backend/KV changes need forced re-init.** `initializeAsync()` short-circuits when the engine is ready, so a loaded engine ignores a later `runtimeConfig` change. `applyAgentConfig` differentiates: runtime changed → force release + re-init + `startConversation`; else → `startConversation` only. Add a force-reinit path to `LiteRtLlmEngine`.
- **G2 — Saving mid-conversation resets context.** `startConversation` recreates the native conversation; saving drops current KV context. UX: confirm on Save when a conversation is active, then reset the session.
- **G4 — Seed rule.** `GenerationConfig.seed` (nullable) → `SamplerConfig.seed` (primitive): null → `0`, else carried through.
- **G7 — `maxOutputTokens` mapping.** Drives the per-call `generate(..., maxTokens = ...)`; `SamplerConfig.maxOutputToken` stays unset; `EngineConfig.maxNumTokens` remains KV capacity.
- **G5 — Test-prompt tool safety.** Test Prompt runs the real agent path and can invoke real tools; intended for the power-user playground, with a visible confirmation for risky actions.

## Capabilities

### New Capabilities

- `agent-playground`: Model & Agent configuration Compose UI with progressive disclosure, system prompt editing, response-behavior preset, collapsible Advanced section (generation + backend + KV), persistence, and live single-turn test prompting.

### Modified Capabilities

- `llm-engine`: `LiteRtLlmEngine` must apply `AgentConfig.generationConfig` to inference (`SamplerConfig` + `maxTokens`) so exposed parameters are honored. `runtimeConfig` (backend, KV capacity) continues to apply as before.
- `conversation`: `ConversationManager`/`ConversationSession` must accept and apply an `AgentConfig` (merged system prompt + generation config + runtime config) rather than building a hardcoded prompt.

## Impact

### `core:conversation` (new work — not in the original plan)
- `ConversationManager.kt`: hold an active `AgentConfig`; pass it to `newChatSession()`/`newVoiceSession()`; add a re-apply/reset method that re-runs `startConversation(agentConfig)`.
- `ConversationSession.kt`: accept an `AgentConfig`; merge `systemInstruction` with the tool prompt in `buildSystemPrompt()`; call `startConversation(agentConfig)`; pass `maxOutputTokens`/generation config through `generate()` instead of relying on the 256 default.
- `ConversationContext.kt`: possibly minor — support config-driven reset/trim if needed.

### `core:llm`
- `LiteRtLlmEngine.kt`: wire `GenerationConfig` → `SamplerConfig`/`ConversationConfig`, pass `maxTokens` → `sendMessageAsync`, and add a **force re-init** path (so a loaded engine re-applies `RuntimeConfig` backend/KV changes).
- `LlmEngine.kt`: interface (hooks already exist; confirm force-reinit hook).
- Test: `LlmEngineTest.kt` + `ConversationManagerTest.kt`/`ConversationSessionTest.kt` (new config-threading coverage).

### `core:ui`
- New `AgentPlaygroundScreen.kt`, `AgentPlaygroundViewModel.kt`, **new `AgentConfigRepository` (DataStore)**.
- `ChatScreen.kt`: add a top-bar entry point (pass `onNavigateToAgentPlayground`).
- `SetupScreen.kt`: add a Playground entry point (new callback).

### `app`
- `MainActivity.kt`: add `AppScreen.AGENT_PLAYGROUND`, a new routing branch, and construct the `AgentPlaygroundViewModel`.
- `AppModule.kt`: provide the `AgentConfigRepository` and wire it + `ConversationManager`.
- `LokiApplication.kt`: maybe — DataStore delegate.

- **Persistence decision**: `AgentConfigRepository` (DataStore Preferences) lives in **`core:ui`** (which already depends on DataStore). `core:conversation`, the consumer, does not currently depend on DataStore and stays clean; `MainActivity` loads the config and pushes it into `ConversationManager` at start. Keep global/default config for v1; leave the architecture open to per-model defaults later (keyed by `ModelRecord.id`).
- **Dependencies**: No new external dependency. `core:ui` already has `androidx.datastore.preferences` and Compose/Material 3. `core:conversation` gains the `AgentConfig` type only via existing `core:models`/`core:llm` lines.
- **Out of scope (deferred, not built speculatively):** Thinking Mode, speculative decoding, NPU/NNAPI backends, a full Prompt Lab, per-model defaults, and new tool-calling controls. These may be introduced later through the capability architecture (`ModelCapabilities`) when Loki has a concrete model or use case that benefits.
