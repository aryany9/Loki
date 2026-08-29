## Why

Loki's current LLM engine implementation (`LiteRtLlmEngine`) uses hardcoded KV cache capacity parameters, lacks structured configuration abstractions for generation parameters, and cannot dynamically adapt to different model capabilities or execution backends. To support user-controlled model settings, honest runtime capabilities, and reliable session lifecycle management, Loki requires a capability-driven LLM engine configuration architecture before building the Agent Playground UI (Change 3) and full Voice Assistant workflow (Change 4).

## What Changes

- Introduce structured configuration classes: `AgentConfig` wrapping `systemInstruction`, `GenerationConfig` (temperature, topK, topP, seed, maxOutputTokens), and `RuntimeConfig` (execution backend: `AUTOMATIC`/`GPU`/`CPU`, and model-constrained `contextKvCapacity`).
- Decouple `contextKvCapacity` (runtime/model-constrained context window memory allocation) from `maxOutputTokens` (generation turn output length limit); validate requested context capacity against what the selected LiteRT-LM model/runtime supports rather than hard-coding static values.
- Introduce `ModelCapabilities` reporting (text generation, tool calling, audio input, vision) based on model metadata and engine inspection.
- Update `LiteRtLlmEngine` to accept `AgentConfig` dynamically during session initialization, configuring `ConversationConfig.systemInstruction`, `EngineConfig.backend`, and engine parameters cleanly.
- Support `ExecutionBackend` selection (`AUTOMATIC`, `GPU`, `CPU`) with capability-aware automatic hardware detection and CPU fallback.
- Validate native process cancellation (`activeConversation?.cancelProcess()`) to guarantee immediate token generation interruption without corrupting native runtime state.
- Establish the clean engine-capability interface that Change 3 (Agent Playground UI) and Change 4 (Voice Assistant Workflow) depend on, while keeping STT and TTS providers completely decoupled from `LiteRtLlmEngine`.

## Capabilities

### New Capabilities

- `engine-capabilities`: Capability-driven LLM runtime configuration (`AgentConfig`, `GenerationConfig`, `RuntimeConfig`), model capability reporting (`ModelCapabilities`), dynamic backend selection with fallback, context capacity validation, and token-level native process cancellation.

### Modified Capabilities

- `llm-engine`: Enhanced `LlmEngine` interface and `LiteRtLlmEngine` implementation to support structured configuration models, dynamic system instructions, runtime capability declarations, and non-blocking process cancellation.

## Impact

- `core:llm`: Refactor `LiteRtLlmEngine`, `LlmEngine`, and `ModelTypes` to support `AgentConfig`, `GenerationConfig`, `RuntimeConfig`, `ModelCapabilities`, and `ExecutionBackend`.
- Memory & Runtime: Validate context KV cache allocation against model specifications dynamically at runtime initialization.
- Dependencies: None added; uses existing LiteRT-LM SDK APIs (`com.google.ai.edge.litertlm.*`).
- Compatibility: Defer STT/TTS integration to Change 4 and configuration UI screens to Change 3, keeping Change 2 strictly focused on the LLM engine runtime contracts.
