## Why

Loki currently contains legacy multi-backend complexity, including embedded `llama.cpp` native sources, GGUF heuristics, MediaPipe GenAI remnants, and speculative multi-runtime abstractions (`DelegatingLlmEngine`). Additionally, the Kotlin compiler fails on LiteRT-LM's binary metadata because the toolchain versions are misaligned. 

Loki must transition to a clean, production-ready, pure-Kotlin architecture where **LiteRT-LM** is the **sole inference runtime** and **`.litertlm`** is the **sole supported model format**. The Kotlin/AGP/Compose toolchain must be properly aligned with LiteRT-LM so that the application consumes LiteRT-LM's Kotlin APIs natively without compiler workarounds or Java shims.

## What Changes

- **Toolchain Alignment (No Workarounds)**: Establish an exact, stable, mutually compatible version matrix for JDK 21, Gradle, AGP, Kotlin, KSP, Compose, and LiteRT-LM Android. Prohibit Java compatibility bridges (`LiteRtLlmJavaHelper.java`) and compiler suppression flags (`-Xskip-metadata-version-check`).
- **Pure-Kotlin Engine Implementation**: Implement `LiteRtLlmEngine.kt` as the sole concrete `LlmEngine` implementation, consuming LiteRT-LM's Kotlin API (`Engine`, `EngineConfig`, `Conversation`, `Flow<Message>`) directly.
- **GPU-Preferred / CPU-Fallback Policy**: Prefer GPU backend initialization with automatic fallback to CPU *only* on genuine backend/device initialization failures. Fail immediately with exact root-cause errors on corrupt, invalid, or unsupported model artifacts without triggering CPU retries.
- **Complete Legacy Runtime Removal**: **[BREAKING]** Delete `llama.cpp` native C++ sources (`core/llm/src/main/cpp/llama.cpp`), JNI bridge (`loki_llama_bridge.cpp`), CMakeLists, and NDK build configuration in `core:llm`. Delete `LlamaCppLlmEngine.kt`, `LlamaBridge.kt`, `GrammarBuilder.kt`, and `DelegatingLlmEngine.kt`.
- **Remove MediaPipe GenAI**: Remove `com.google.mediapipe:tasks-genai` and associated configurations.
- **Streamline Model Management**: Refactor `ModelManager`, `ModelRegistry`, `ModelRecord`, and `ModelLibraryManager` to manage `.litertlm` models exclusively. Remove GGUF, `.bin`, and multi-runtime enums/heuristics.
- **Evidence-Based Model Validation**: Implement layered validation using LiteRT-LM's supported validation mechanisms without inventing custom binary header parsers.
- **Streamline DI & UI**: Provide `LiteRtLlmEngine` directly via Hilt in `AppModule.kt`. Remove multi-runtime selection controls and GGUF branches from `MainActivity.kt` and `ModelLibraryScreen.kt`.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `llm-engine`: Transition `LlmEngine` requirements from llama.cpp/GGUF to a pure-Kotlin LiteRT-LM single-runtime engine, native streaming token emission via coroutine flows, isolated GPU $\rightarrow$ CPU fallback, and `.litertlm` artifact lifecycle.
- `model-library`: Streamline model persistence, managed storage, import, deletion, and validation to strictly support `.litertlm` models with no multi-runtime selection.

## Impact

- **Toolchain & Build**: `gradle/libs.versions.toml`, root `build.gradle.kts`, and module build files aligned to compatible Kotlin, AGP, KSP, Compose, and LiteRT-LM versions. Removal of `externalNativeBuild` and CMake from `core:llm`.
- **`core:llm`**: Major cleanup. Removal of all C++ sources, JNI bindings, llama.cpp classes, and `DelegatingLlmEngine`. Implementation of Kotlin-native `LiteRtLlmEngine`. Simplification of model management classes to `.litertlm`.
- **`core:ui` & `app`**: Removal of multi-runtime UI selectors, GGUF validation branches, and delegating engine wiring. Direct injection of `LiteRtLlmEngine`.
- **Dependencies**: Addition of resolved compatible `litertlm-android` and removal of `mediapipe:tasks-genai`.
