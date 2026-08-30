# Design: LLM Engine Capabilities & Runtime Configuration

## Overview

This design defines the capability-driven engine configuration architecture for Loki's primary LLM engine (`LiteRtLlmEngine`). It introduces structured configuration models, distinguishes context KV cache memory capacity from output generation limits, implements dynamic backend selection with hardware fallback, and exposes model capabilities.

## Architecture & Data Model

```text
                               AgentConfig
                                    │
           ┌────────────────────────┼────────────────────────┐
           ▼                        ▼                        ▼
   System Instruction       GenerationConfig           RuntimeConfig
 (systemInstruction text)   ├── temperature            ├── backend (AUTOMATIC/GPU/CPU)
                            ├── topK                   └── contextKvCapacity
                            ├── topP                       (validated against model)
                            ├── seed
                            └── maxOutputTokens
```

### Configuration Classes

```kotlin
data class AgentConfig(
    val systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    val generationConfig: GenerationConfig = GenerationConfig(),
    val runtimeConfig: RuntimeConfig = RuntimeConfig()
)

data class GenerationConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val seed: Int? = null,
    val maxOutputTokens: Int? = null
)

data class RuntimeConfig(
    val backend: ExecutionBackend = ExecutionBackend.AUTOMATIC,
    val contextKvCapacity: Int? = null // Validated dynamically against model specification
)

enum class ExecutionBackend {
    AUTOMATIC,
    GPU,
    CPU
}

data class ModelCapabilities(
    val supportsText: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val supportsAudioInput: Boolean = false,
    val supportsVisionInput: Boolean = false
)
```

## Key Requirements & Technical Strategies

### 1. KV Cache Capacity vs Max Output Tokens
- **`contextKvCapacity`**: Allocated in `EngineConfig.maxNumTokens` during native engine initialization. The engine validates requested capacity against model metadata and minimum runtime bounds (e.g. 2048 to 8192). It is **never** hardcoded as a universal static value.
- **`maxOutputTokens`**: Generation constraint passed per turn/session. Prevents long-winded generations without restricting the input KV cache window.

### 2. Execution Backend & Fallback Strategy
- `AUTOMATIC`: Attempts `Backend.GPU()` first. If GPU initialization or sampler creation fails with a hardware/driver exception (e.g., OpenCL/Vulkan driver missing), automatically falls back to `Backend.CPU()`.
- `GPU` / `CPU`: Explicit user selection. If an explicit choice fails, reports an explicit error without silent downgrade, unless fallback policy is requested.

### 3. Native Session Cancellation
- Calling `LiteRtLlmEngine.cancel()` triggers `activeConversation?.cancelProcess()`.
- Halts token generation on the background thread immediately and leaves the native `Conversation` object in a safe state for subsequent turns.

### 4. Integration Boundaries for Future Changes
- **Change 3 (Agent Playground)**: Will bind UI controls directly to `AgentConfig`, `GenerationConfig`, and `RuntimeConfig`.
- **Change 4 (Voice Assistant Workflow)**: Will consume `LiteRtLlmEngine` via `ConversationManager`, passing `maxTurns = 1` voice sessions without coupling ASR (`SttEngine`) or TTS (`TtsEngine`) into the LLM engine.
