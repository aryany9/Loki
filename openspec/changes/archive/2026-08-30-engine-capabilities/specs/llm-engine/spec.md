# Spec: LLM Engine Capabilities & Runtime Configuration

## MODIFIED Requirements

### Requirement: Structured Engine Configuration

The LLM engine MUST support structured configuration via `AgentConfig`, encapsulating system instructions, generation hyperparameters (`GenerationConfig`), and runtime settings (`RuntimeConfig`).

#### Scenario: Initializing engine with custom AgentConfig
- **GIVEN** an `AgentConfig` specifying system instruction, temperature 0.5, topK 30, topP 0.9, and AUTOMATIC backend
- **WHEN** `initializeAsync()` or `startConversation()` is called on `LiteRtLlmEngine`
- **THEN** the engine initializes using the specified system instruction and backend settings without hardcoded overrides.

### Requirement: Dynamic KV Cache Capacity Validation

The engine MUST distinguish context KV cache capacity (`contextKvCapacity`) from output generation token limits (`maxOutputTokens`), and validate `contextKvCapacity` dynamically against model capabilities.

#### Scenario: Validating KV cache capacity against model limit
- **GIVEN** a model record with max supported context window
- **WHEN** configuring `RuntimeConfig.contextKvCapacity`
- **THEN** the engine validates the requested capacity, using model-supported limits rather than hardcoded defaults.

### Requirement: Backend Selection & Fallback

The engine MUST support `ExecutionBackend` selection (`AUTOMATIC`, `GPU`, `CPU`) and handle hardware fallback safely when set to `AUTOMATIC`.

#### Scenario: GPU backend initialization failure under AUTOMATIC mode
- **GIVEN** `ExecutionBackend.AUTOMATIC` is configured on a device without GPU acceleration support
- **WHEN** GPU engine initialization fails
- **THEN** the engine logs the GPU failure diagnostic and automatically retries initialization using the CPU backend.

### Requirement: Token-Level Native Cancellation

Calling `cancel()` on `LiteRtLlmEngine` MUST invoke native `Conversation.cancelProcess()` to stop active generation immediately.

#### Scenario: User cancels active generation turn
- **GIVEN** an active token generation loop running on `LiteRtLlmEngine`
- **WHEN** `cancel()` is called
- **THEN** native token generation is cancelled immediately and the engine returns a cancelled status cleanly.
