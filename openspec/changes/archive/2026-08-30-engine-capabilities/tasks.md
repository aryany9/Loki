## Tasks

- [x] 1. Define configuration models (`AgentConfig`, `GenerationConfig`, `RuntimeConfig`, `ExecutionBackend`, `ModelCapabilities`) in `core/llm/src/main/java/dev/loki/android/core/llm/ModelTypes.kt`.
- [x] 2. Update `LlmEngine` interface in `core/llm/src/main/java/dev/loki/android/core/llm/LlmEngine.kt` to accept `AgentConfig` and expose `ModelCapabilities`.
- [x] 3. Implement dynamic KV cache capacity validation in `LiteRtLlmEngine.kt`, removing hardcoded static token limits and checking against model metadata.
- [x] 4. Refactor `LiteRtLlmEngine.kt` backend initialization logic to support `ExecutionBackend` selection (`AUTOMATIC`, `GPU`, `CPU`) with clean diagnostic logging and fallback handling.
- [x] 5. Verify native cancellation behavior in `LiteRtLlmEngine.kt` by ensuring `activeConversation?.cancelProcess()` is called and handled gracefully during stream flow collection.
- [x] 6. Update unit tests in `core/llm/src/test/java/dev/loki/android/core/llm/LiteRtLlmEngineTest.kt` to cover `AgentConfig`, backend selection, and cancellation contracts.
