## 0. Baseline & Toolchain Alignment

- [x] 0.1 Document current baseline versions (JDK 21, Gradle 8.14.5, AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Hilt 2.52, LiteRT-LM) and capture the exact FIR Kotlin compiler metadata mismatch error.
- [x] 0.2 Inspect the latest stable LiteRT-LM Android artifact releases (0.13.1 vs 0.14.0+) and select the newest stable version compatible with Loki functionality.
- [x] 0.3 Establish an exact, stable, mutually compatible version matrix across JDK 21, Gradle 8.14.5, AGP, Kotlin, KSP, Compose compiler plugin, and LiteRT-LM in `gradle/libs.versions.toml` and build configurations without compiler workarounds or Java bridges.

## 1. Pure Kotlin LiteRtLlmEngine & Direct Compatibility Proof

- [x] 1.1 Implement actual `LiteRtLlmEngine.kt` directly in Kotlin consuming LiteRT-LM's Kotlin APIs (`Engine`, `EngineConfig`, `Conversation`, `Flow<Message>`) with an explicit Engine (model-level) vs Conversation (dialogue-level) lifecycle.
- [x] 1.2 Implement GPU-preferred initialization in `LiteRtLlmEngine` with isolated, conservative error classification for CPU fallback and fail-fast behavior on model/artifact corruption.
- [x] 1.3 Implement streaming token delivery via LiteRT-LM Kotlin `Flow<Message>` and coroutines.
- [x] 1.4 Implement explicit native cancellation via LiteRT-LM's conversation cancellation APIs ensuring no lingering native background inference.
- [x] 1.5 Ensure idempotent lifecycle (`release()`, model reload/switch) with zero native resource leaks.
- [x] 1.6 Verify direct Kotlin compilation and minimal inference in `:core:llm` without Java shims or compiler suppression flags. (If Kotlin/LiteRT-LM incompatibility occurs, stop and fix toolchain).

## 2. Legacy Runtime Removal

- [x] 2.1 Delete `llama.cpp` C++ sources (`core/llm/src/main/cpp/llama.cpp`), `loki_llama_bridge.cpp`, and `CMakeLists.txt` in `core:llm`.
- [x] 2.2 Remove `externalNativeBuild` and CMake/NDK configurations from `core/llm/build.gradle.kts`.
- [x] 2.3 Delete `LlamaCppLlmEngine.kt`, `LlamaBridge.kt`, `GrammarBuilder.kt`, `DelegatingLlmEngine.kt`, and `LiteRtLlmJavaHelper.java`.
- [x] 2.4 Remove MediaPipe GenAI dependency (`com.google.mediapipe:tasks-genai`) and all unused multi-runtime dependencies from `libs.versions.toml` and Gradle files.

## 3. Model Layer Migration

- [x] 3.1 Update `ModelTypes.kt` to simplify `ModelRecord` and enums for `.litertlm` only, removing GGUF, `.bin`, and multi-runtime enums.
- [x] 3.2 Update `ModelManager.kt`, `ModelRegistry.kt`, `ModelStorage.kt`, and `ModelLibraryManager.kt` to operate strictly on `.litertlm` models and maintain zero live runtime/Engine handles.
- [x] 3.3 Refactor `ModelValidation.kt` to provide layered validation for `.litertlm` files using LiteRT-LM supported validation without custom binary parsers.
- [x] 3.4 Update `ModelCatalog.kt` and `LegacyModelMigrator.kt` to remove GGUF/llama.cpp legacy migration paths.

## 4. DI & UI Integration

- [x] 4.1 Update `AppModule.kt` to bind `LiteRtLlmEngine` directly as the `@Singleton` `LlmEngine` implementation.
- [x] 4.2 Update `ModelLibraryScreen.kt` to remove multi-runtime toggle buttons and simplify to `.litertlm` model management.
- [x] 4.3 Update `MainActivity.kt` import flow to detect and validate `.litertlm` models exclusively.

## 5. Testing, Verification & Regression Checks

- [x] 5.1 Implement unit tests for `LiteRtLlmEngine` (initialization, GPU/CPU fallback policy, generation, explicit cancellation, idempotent release).
- [x] 5.2 Implement unit tests for `.litertlm` model validation, storage, and registry reconciliation.
- [x] 5.3 Verify resolved dependency graphs (`./gradlew :core:llm:dependencies`) to confirm complete absence of MediaPipe, llama.cpp, and CMake.
- [x] 5.4 Perform a repository-wide regression check ensuring zero Java compatibility shims (`LiteRtLlmJavaHelper.java`), zero `-Xskip-metadata-version-check`, and zero compiler suppressions exist.
- [x] 5.5 Run full test suites (`./gradlew test` and `:core:llm:test`) and debug builds (`./gradlew assembleDebug`).
- [x] 5.6 Verify on a real Android device using a known-good LiteRT-LM-compatible `.litertlm` model fixture (successful load, streaming inference, explicit cancellation, GPU detection/CPU fallback, unload/reload).
