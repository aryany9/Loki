# Implementation Plan: Simplify Loki to a LiteRT-LM-First Android LLM Architecture

Simplify Loki's LLM architecture by focusing exclusively on LiteRT-LM as the inference runtime and `.litertlm` as the supported model format. This involves removing the multi-runtime abstraction (llama.cpp/GGUF) and optimizing for Android-native GPU/CPU acceleration.

## User Review Required

> [!IMPORTANT]
> This change removes support for GGUF models and llama.cpp. Existing GGUF models in the user's storage will no longer be usable by Loki.

## Proposed Changes

### Core LLM Component

#### [MODIFY] [ModelTypes.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/ModelTypes.kt)
- Remove `LLAMA_CPP` from `ModelRuntime`.
- Remove `GGUF` from `ModelFormat`.
- Simplify `ModelRecord` and related types to reflect the LiteRT-LM focus.

#### [MODIFY] [LlmEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/LlmEngine.kt)
- Remove `runtime` parameter from `initializeAsync` if it's no longer needed.
- Simplify the interface for a single runtime.

#### [MODIFY] [LiteRtLlmEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/LiteRtLlmEngine.kt)
- Implement GPU to CPU fallback during initialization.
- Refine streaming generation and resource management (Engine/Conversation lifecycle).
- Ensure model validation through initialization.

#### [DELETE] [LlamaCppLlmEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/LlamaCppLlmEngine.kt)
- Remove the llama.cpp engine implementation.

#### [DELETE] [DelegatingLlmEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/DelegatingLlmEngine.kt)
- Remove the multi-engine delegator.

#### [MODIFY] [build.gradle.kts](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/build.gradle.kts)
- Remove `llama.cpp` CMake configuration and externalNativeBuild.
- Remove `mediapipe-tasks-genai` dependency if unused.

### Dependency Injection

#### [MODIFY] [AppModule.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/app/src/main/java/dev/loki/android/di/AppModule.kt)
- Directly provide `LiteRtLlmEngine` as the `LlmEngine` implementation.
- Remove logic for runtime selection.

## Verification Plan

### Automated Tests
- Update `LlmEngineTest.kt` to verify `LiteRtLlmEngine` specific behaviors like GPU/CPU fallback.
- Run `./gradlew :core:llm:test`

### Manual Verification
1. Deploy Loki to an Android device.
2. Import a `.litertlm` model (Qwen3-4B).
3. Verify successful initialization on GPU.
4. Force CPU mode (via settings or by inducing GPU failure) and verify fallback.
5. Perform a chat conversation to verify streaming generation and conversation history.
6. Verify that GGUF models are no longer detected or selectable.
