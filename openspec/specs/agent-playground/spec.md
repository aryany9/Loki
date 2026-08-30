## Purpose
Interactive playground screen for configuring and testing agent behavior against the local LLM.

## Requirements

### Requirement: Generation Parameters Affect Inference

The system MUST apply `AgentConfig.generationConfig` (temperature, topK, topP, seed) and `maxTokens` to LiteRT-LM inference so that parameters exposed in the Playground are honored by the engine, not merely persisted.

#### Scenario: Sampling parameters reach the model
- **GIVEN** an active conversation via `startConversation(agentConfig)`
- **WHEN** `AgentConfig.generationConfig` specifies temperature 0.3, topK 20, topP 0.9, seed 42
- **THEN** the engine creates the native conversation with a `SamplerConfig` carrying those values, and calls to `generate(..., maxTokens)` pass `maxTokens` through to `sendMessageAsync`.

#### Scenario: Engine applied parameters are observable in Playground
- **GIVEN** a power user adjusts Advanced sampling controls and saves
- **WHEN** the user runs a Test Prompt
- **THEN** the observed output/tool behavior reflects the configured generation parameters.

### Requirement: Conversation-Layer Applies AgentConfig

The system MUST thread `AgentConfig` through `ConversationManager` → `ConversationSession` so the persisted system prompt and generation config drive real chat/voice inference, not just the engine contract. The conversation session SHALL merge `AgentConfig.systemInstruction` with its dynamically-built tool prompt and call `startConversation(agentConfig)` (full `AgentConfig` overload), passing generation config / `maxOutputTokens` through `generate()`.

#### Scenario: Persisted system prompt reaches chat inference
- **GIVEN** `AgentConfigRepository` holds a custom `systemInstruction` and an advanced `generationConfig`
- **WHEN** a user sends a message through `ConversationSession.processUtterance()`
- **THEN** the session starts the conversation with the full `AgentConfig` (merged prompt + `SamplerConfig`), and `generate()` receives the configured `maxOutputTokens` rather than the 256 default.

#### Scenario: Saving config re-applies to the active conversation
- **GIVEN** the user is in a conversation
- **WHEN** they save a changed `AgentConfig` in the Playground
- **THEN** `ConversationManager.applyAgentConfig(agentConfig)` re-runs `startConversation(agentConfig)` so the new generation parameters take effect on the active conversation.

#### Scenario: Runtime changes force engine re-initialization
- **GIVEN** a loaded engine and an `AgentConfig` whose `runtimeConfig` (backend or KV capacity) has changed
- **WHEN** the config is applied via `applyAgentConfig`
- **THEN** the engine force-releases and re-initializes with the new `RuntimeConfig` (`initializeAsync(runtimeConfig, force = true)` style path) before re-running `startConversation`, so the backend/KV change is honored rather than ignored by the ready-engine short-circuit.

#### Scenario: Saving mid-conversation confirms context reset
- **GIVEN** an active conversation with prior turns
- **WHEN** the user saves a changed `AgentConfig`
- **THEN** the UI shows a confirmation that the current conversation will restart, and on confirmation the conversation session is reset so the new config takes effect without silent context loss.

### Requirement: Model & Agent Configuration UI (progressive disclosure)

The system MUST provide a dedicated configuration screen organized via progressive disclosure: a top-level Model & Agent area (model info, system prompt, response behavior, test prompt) and a collapsible Advanced section hiding raw sampling/runtime controls by default.

#### Scenario: Normal user flow
- **GIVEN** the Agent Playground screen is open
- **WHEN** the user edits the System Prompt, chooses a response-behavior preset, runs a Test Prompt, and saves
- **THEN** the updated `AgentConfig` is validated, persisted, and the active conversation is refreshed so the new settings take effect, without the user ever needing to open Advanced.

#### Scenario: Power-user advanced flow
- **GIVEN** a power user expands the Advanced section
- **WHEN** the user adjusts Temperature (0.0–2.0), Top-K (1–100), Top-P (0.0–1.0), Seed, Max Output Tokens, Execution Backend (Automatic/GPU/CPU), and Context/KV capacity
- **THEN** the values are validated and applied on save, and remain presented as runtime/intelligence tuning rather than first-class controls for a normal user.

### Requirement: Response Behavior Preset

The configuration UI MUST provide a high-level response-behavior preset (e.g. Fast / Balanced / Precise) that maps to a `GenerationConfig` as a UX abstraction, without introducing a new inference capability.

#### Scenario: Selecting a behavior preset
- **GIVEN** the Response behavior section in Agent Playground
- **WHEN** the user chooses "Precise"
- **THEN** the underlying generation parameters are set to a more deterministic profile (e.g. lower temperature/topK), and this is reflected in the active configuration. Editing an Advanced control takes precedence over the preset.

### Requirement: Test Prompt Execution

The system MUST provide a live, single-turn test prompt runner within the Agent Playground based on `ConversationSession.processUtterance()` and using the active model configuration, so the real agent path (merged system prompt, generation config, tool parsing, diagnostics) is exercised.

#### Scenario: Running a test prompt in Playground
- **GIVEN** an active loaded model and customized system prompt
- **WHEN** the user types a prompt and taps "Run Test Prompt"
- **THEN** the model output and tool execution diagnostics are displayed in the playground output area.
