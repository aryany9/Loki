## Why

With Change 1 (`model-library`) and Change 2 (`engine-capabilities`) complete, Loki can manage multiple models and configure LLM runtime capabilities (`AgentConfig`, `GenerationConfig`, `RuntimeConfig`). However, users currently have no UI interface to customize system prompts, tune generation hyperparameters (temperature, topK, topP, seed, maxOutputTokens), select execution backends, or test model behavior. The Agent Playground provides a dedicated configuration and testing interface before building the full end-to-end Voice Assistant workflow (Change 4).

## What Changes

- Add a dedicated **Model & Agent Configuration** screen (`ModelPlaygroundScreen` / `AgentPlaygroundScreen`) accessible from app settings and navigation routing.
- Display selected model status, runtime format, KV context window capacity, and capability flags (`ModelCapabilities`).
- Provide an editable text field for the **System Prompt** (`systemInstruction`).
- Add controls for generation hyperparameters: **Temperature** (0.0 to 2.0 slider), **Top-K** (1 to 100 slider), **Top-P** (0.0 to 1.0 slider), **Seed**, and **Max Output Tokens**.
- Add a radio selector for **Execution Backend**: `(•) Automatic`, `( ) GPU`, `( ) CPU`.
- Add provider selectors for Speech-to-Text (ASR) and Text-to-Speech (TTS) providers (`Android System TTS` vs `Custom Local Model`), laying the configuration seam for Change 4.
- Provide `[ Reset Defaults ]`, `[ Save Configuration ]`, and a `[ Test Prompt ]` execution area allowing live prompt testing against the active model configuration.
- Integrate the screen into `AppScreen` navigation routing.

## Capabilities

### New Capabilities

- `agent-playground`: Dedicated Model & Agent Configuration Compose UI, hyperparameter editing (`AgentConfig`, `GenerationConfig`, `RuntimeConfig`), provider selection UI, persistence, and live prompt testing.

### Modified Capabilities

- None. Uses `AgentConfig` contracts from Change 2 and `ModelRecord` contracts from Change 1.

## Impact

- `core:ui`: Add `AgentPlaygroundScreen.kt` and `AgentPlaygroundViewModel.kt` handling state collection, hyperparameter sliders, provider selectors, and test prompt execution.
- `app`: Update `AppScreen` routing and navigation entry points.
- Persistence: Persist user's active `AgentConfig` preference via DataStore Preferences.
- Dependencies: None added; uses standard Compose and Material 3 controls.
