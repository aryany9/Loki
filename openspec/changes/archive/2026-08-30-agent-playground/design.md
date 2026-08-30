# Design: Agent Playground & Model Configuration UI

## Overview

This design specifies the Compose UI, state management, configuration persistence, and **engine wiring** for the **Agent Playground**. It bridges user configuration to the `AgentConfig` data model (Change 2) and — critically — makes sure the exposed generation parameters actually reach LiteRT-LM inference.

**Two-layer complexity.** The engine keeps full expressive power (`AgentConfig`); the UI hides it behind an **Advanced** section via progressive disclosure. A normal Loki user interacts only with System Prompt + a high-level behavior preset. A power user/developer can open Advanced and tune raw sampling/runtime controls.

## Engine wiring prerequisite (`core:llm`)

> Mandatory. Not "complete" if parameters are merely persisted but not applied.

Current `LiteRtLlmEngine` behavior confirmed against LiteRT-LM `0.16.1`:

- `runtimeConfig.backend` and `runtimeConfig.contextKvCapacity` → correctly applied at init (`initializeAsync(runtimeConfig)`).
- `generationConfig` (temperature, topK, topP, seed) → **NOT applied** today.
- `generate(..., maxTokens)` → `maxTokens` is **NOT** passed to `conversation.sendMessageAsync(...)`.

### Changes to `LiteRtLlmEngine`

In `startConversation(agentConfig)`, populate the native config from `generationConfig`:

```
samplerConfig = SamplerConfig(
    topK        = generationConfig.topK,
    topP        = generationConfig.topP,
    temperature = generationConfig.temperature,
    seed        = generationConfig.seed ?: DEFAULT_SEED /* API requires a value; use 0 default */)

convConfig = ConversationConfig(
    systemInstruction = Contents.of(agentConfig.systemInstruction),
    samplerConfig     = samplerConfig)
                          // maxOutputToken intentionally unset — see G7

activeConversation = currentEngine.createConversation(convConfig)
```

In `generate(...)`, pass `maxTokens` (from `AgentConfig.maxOutputTokens`, or the default cap) through to the per-call overload:

```
conversation.sendMessageAsync(userMessage, maxTokens = maxTokens)
```

Notes:
- Sampling params are fixed at conversation creation and prefilled into the KV cache; this matches Loki's existing persistent-Conversation design. Changing them requires a conversation reset (`startConversation` again).
- `seed` maps to the native `SamplerConfig` seed; Loki exposes it as optional. **Rule (resolved):** null → `0`; otherwise the value is carried through. (G4)
- **`maxOutputTokens` mapping (resolved):** `AgentConfig.maxOutputTokens` drives the **per-call** `generate(..., maxTokens = ...)`, not `SamplerConfig.maxOutputToken`. `SamplerConfig.maxOutputToken` stays unset/null as the native conversation-level cap; only the per-call value bounds each turn. `EngineConfig.maxNumTokens` remains the KV capacity, distinct from max output tokens. Avoids double-capping. (G7)

### Backend / KV changes require forced re-initialization (G1)

`LiteRtLlmEngine.initializeAsync()` short-circuits with `if (isReady()) return true`, so a loaded engine ignores a later `runtimeConfig` (backend / KV) change. To honor Advanced changes that are already loaded, `applyAgentConfig` must differentiate the change kind:

```
agentConfig applied to a loaded engine:
  if runtimeConfig changed (backend or KV capacity):
      force releaseNativeResources() + reinitializeAsync(runtimeConfig) + startConversation(agentConfig)
  else (only generationConfig / systemInstruction changed):
      startConversation(agentConfig)          // no engine re-init; sampling params re-buffered
```

This requires exposing a "force re-init" path on `LiteRtLlmEngine` (e.g. an `initializeAsync(runtimeConfig, force = true)` or a dedicated `reconfigureRuntime()`), since the current early-return prevents re-application.

## Conversation-layer integration (`core:conversation`)

> Mandatory. The System Prompt editor and generation config are only meaningful if they flow into real chat/voice inference.

Current gap (audited):
- `ConversationManager` holds no `AgentConfig`; `newChatSession()`/`newVoiceSession()` build sessions without it.
- `ConversationSession.buildSystemPrompt(availableTools, disabledTools)` **hardcodes** its own base instruction and never reads `AgentConfig.systemInstruction`.
- `ConversationSession.generate()` calls `llmEngine.generate(prompt, audioBytes, onToken = null)` → `maxTokens` falls back to the engine's **256 default**; `AgentConfig.maxOutputTokens` is ignored.

### Changes to `ConversationManager`
- Hold an active `AgentConfig` (set via a new `setAgentConfig(agentConfig)` / `updateAgentConfig(...)`).
- Pass it into every `newChatSession()` / `newVoiceSession()`.
- Add a **re-apply** method (e.g. `applyAgentConfig(agentConfig)`) that updates the stored config and re-runs `startConversation(agentConfig)` on the active conversation so sampling params take effect.
- Load the persisted config from `AgentConfigRepository` at app start (via `MainActivity`/DI) so the assistant honors the saved settings.

### Changes to `ConversationSession`
- Accept an `agentConfig: AgentConfig` (default = `AgentConfig()`).
- In `buildSystemPrompt(...)`, **merge** using a 3-tier layered structure:
  1. Base Persona & Immutable Guardrails: `"You are Loki, a private offline Android assistant running on the user's device..."` and core privacy/safety rules that cannot be removed.
  2. Custom User Instructions: append `agentConfig.systemInstruction` (when set).
  3. Dynamic Tool Signatures & Output Contract: list available/disabled tools and required JSON format (`{"tool": "..."}` or `{"response": "..."}`).
- Call `llmEngine.startConversation(agentConfig)` (full `AgentConfig` overload) instead of the string overload, so `systemInstruction`, `SamplerConfig`, and `maxOutputToken` are applied together.
- Pass `agentConfig.generationConfig.maxOutputTokens` (or a per-call override) through `generate(..., maxTokens = ...)` instead of relying on the 256 default.

### Test Prompt semantics
The Test Prompt runs a **single-turn** `ConversationSession.processUtterance()` path (not a raw `llmEngine.generate()`). This exercises the real agent pipeline — merged system prompt, generation config, tool parsing, and diagnostics — against the active `AgentConfig`, avoiding a second divergent inference path.

### Capability source (Model section)
- LOADED model → use the loaded engine's `ModelCapabilities` (text / tool / audio / vision).
- Not loaded → fall back to persisted `ModelRecord` / `ModelRecordCapabilities` (audio flag) plus catalog `capabilities`. No new metadata in this change.

## UI Screen Layout & Flow (progressive disclosure)

```text
┌─────────────────────────────────────────────────────────────────┐
│ ⚙️ Model & Agent                                                │
├─────────────────────────────────────────────────────────────────┤
│ MODEL                                                           │
│   Gemma-4-E4B-it.litertlm        ● LOADED / ○ Not loaded        │
│   Capabilities: [✓ Text] [✓ Tool] [✓ Audio] [✗ Vision]          │
│                                              [ Change Model ] → │
├─────────────────────────────────────────────────────────────────┤
│ AGENT · SYSTEM PROMPT                                           │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ You are Loki, a private offline Android assistant...       │ │
│ └─────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ RESPONSE BEHAVIOR                                               │
│   (•) Fast   ( ) Balanced   ( ) Precise                         │
│   "Maps to generation parameters; not a separate capability."   │
├─────────────────────────────────────────────────────────────────┤
│ TEST PROMPT                                                     │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ What time is it in Tokyo?                                   │ │
│ └─────────────────────────────────────────────────────────────┘ │
│ [ RUN TEST PROMPT ]   Response + tool diagnostics               │
├─────────────────────────────────────────────────────────────────┤
│ ▸ ADVANCED  (collapsible — hidden by default)                   │
│   Temperature:        [ 0.70 ]  (0.0 – 2.0)                     │
│   Top-K:              [ 40   ]  (1 – 100)                       │
│   Top-P:              [ 0.95 ]  (0.0 – 1.0)                     │
│   Seed:               [ Optional ]                              │
│   Max Output Tokens:  [ Optional ]                              │
│   Backend:            (•) Auto   ( ) GPU   ( ) CPU              │
│   Context / KV capacity: [ 8192 ] (1024 – 16384) — runtime only  │
├─────────────────────────────────────────────────────────────────┤
│               [ RESET DEFAULTS ]   [ SAVE CONFIG ]              │
└─────────────────────────────────────────────────────────────────┘
```

## Data Persistence & State Management

- **Repository**: new `AgentConfigRepository` in **`core:ui`** (already depends on DataStore Preferences). Serializes an `AgentConfig` (defaults + user overrides) to DataStore. `core:conversation` — the consumer — does not depend on DataStore, so `MainActivity` loads the config and pushes it into `ConversationManager` at start.
- **ViewModel**: `AgentPlaygroundViewModel` collects the active `ModelRecord` from `ModelLibraryManager` and `AgentConfig` from `AgentConfigRepository`.
- **Save Action**: Validates input bounds (temperature `0.0..2.0`, topK `1..100`, topP `0.0..1.0`, KV `1024..16384`), persists the updated `AgentConfig` via `AgentConfigRepository`, then calls `ConversationManager.applyAgentConfig(agentConfig)`.
- **applyAgentConfig semantics**: On a loaded engine, if `runtimeConfig` (backend or KV capacity) changed, force release + re-initialize + `startConversation(agentConfig)`; if only generation/system prompt changed, `startConversation(agentConfig)` (no engine re-init). See "Backend / KV changes require forced re-initialization".
- **Mid-conversation save resets context (G2)**: `startConversation` recreates the native conversation and `ConversationSession` re-seeds it only when `turns <= 1`, so saving mid-chat drops the in-progress KV context. UX decision: **show a confirmation on Save when a conversation is active** ("Changing these settings restarts the current conversation"), and reset `ConversationSession` on save. (Resolved: warn + reset.)
- **Response Behavior preset**: pure UX mapping; resolves to a `GenerationConfig` (Fast: temp=0.8, topK=40, topP=0.9, maxTokens=128; Balanced: temp=0.7, topK=40, topP=0.95, maxTokens=256; Precise: temp=0.2, topK=10, topP=0.8, maxTokens=256). Editing any Advanced control switches the preset label to "Custom" and takes precedence. No new capability added.
- **Reset Defaults**: restores the global default `AgentConfig` (`GenerationConfig()` / `RuntimeConfig()`, default system prompt).
- **Test Prompt**: single-turn `ConversationSession.processUtterance()` against the active `LlmEngine` using the Playground `AgentConfig`, displaying raw model output and tool execution diagnostics.
- **Test Prompt tool safety (G5)**: the test prompt runs the real agent path and can invoke real tools. For the power-user playground this is intended (it tests the assistant), but a confirming label/visual is shown so a risky action (call, alarm) is visible. No separate tool-disabled session in v1.
- **Per-model defaults (future)**: v1 uses global defaults. Leave `AgentConfigRepository` keyed so a `ModelRecord.id`-scoped default can be added later without a breaking DataStore schema change (e.g. persist an optional `modelId` alongside the config).

## Navigation & Entry Points

- Add `AppScreen.AGENT_PLAYGROUND` and a routing branch in `MainActivity.kt`; construct the `AgentPlaygroundViewModel` there.
- **Chat top bar** (`ChatScreen.kt`): add a gear/sliders `⚙` icon beside the model-status badge and permissions icon → opens the Playground.
- **Setup screen** (`SetupScreen.kt`): add a secondary "Tune your assistant" / "Advanced" link near the required-model cards → opens the Playground once models are provisioned.
- The **Advanced** disclosure panel lives *inside* the Playground screen (bottom, collapsed by default). There is no separate floating "Advanced button".
