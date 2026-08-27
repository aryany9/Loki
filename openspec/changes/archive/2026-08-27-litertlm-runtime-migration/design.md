## Context

Loki is an Android local voice assistant application that requires a reliable, native on-device LLM engine. Previous iterations included `llama.cpp` (C++ via JNI), GGUF file handling, MediaPipe GenAI, and speculative multi-runtime abstractions (`DelegatingLlmEngine`). An attempt to introduce LiteRT-LM resulted in Kotlin compiler FIR metadata incompatibilities due to mismatched toolchain and artifact metadata versions.

This design establishes a clean, modern, pure-Kotlin architecture centered around **LiteRT-LM** as the single inference runtime and **`.litertlm`** as the single model format, aligning the Android/Kotlin toolchain cleanly without workarounds.

## Goals / Non-Goals

**Goals:**
- Establish an exact, stable, mutually compatible toolchain matrix (JDK 21, Gradle 8.14+, AGP, Kotlin, KSP, Compose, LiteRT-LM).
- Execute a minimal Phase 1 compatibility spike in Kotlin to verify the toolchain against the LiteRT-LM Kotlin API before performing destructive code migrations.
- Implement `LiteRtLlmEngine.kt` natively in Kotlin consuming LiteRT-LM's Kotlin APIs (`Engine`, `EngineConfig`, `Conversation`, `Flow<Message>`).
- Implement an explicit runtime lifecycle: `Engine` is initialized once for the active model; `Conversation` manages user message exchanges; native resources are safely released upon model unload/switch.
- Implement an evidence-based, isolated GPU-preferred policy with automatic fallback to CPU *only* on genuine backend/hardware failure, and fail-fast behavior on model/artifact corruption.
- Verify explicit cancellation stopping native inference cleanly.
- Remove all `llama.cpp` C++ sources, CMake/NDK build configuration, `LlamaBridge`, `LlamaCppLlmEngine`, `DelegatingLlmEngine`, and MediaPipe GenAI.
- Maintain strict separation of concerns: `ModelManager` manages disk artifacts and metadata only, retaining zero live runtime/Engine handles.

**Non-Goals:**
- Creating a Java bridge (`LiteRtLlmJavaHelper.java`) to bypass Kotlin metadata checks.
- Adding compiler flags like `-Xskip-metadata-version-check`.
- Multi-runtime abstraction, runtime selection UI, or multi-engine delegation.
- Custom binary format/header parsers for `.litertlm`.
- Hardcoding a single specific model weight file as an architectural dependency (a known-good LiteRT-LM-compatible model will be used as a test fixture).

## Decisions

### 1. Toolchain Alignment vs. Java Shim
- **Decision**: Align the project's Kotlin compiler, AGP, KSP, and Compose Compiler plugins to directly support LiteRT-LM's Kotlin metadata version. Prohibit Java compatibility bridges (`LiteRtLlmJavaHelper.java`) and compiler suppression flags (`-Xskip-metadata-version-check`).
- **Rationale**: Loki is a Kotlin-first Android app. Introducing Java source files purely to hide Kotlin metadata is technical debt and prevents idiomatic usage of LiteRT-LM's Kotlin coroutine and Flow APIs.
- **Alternatives Considered**: Java helper bridge (rejected: violates Kotlin-first architecture); `-Xskip-metadata-version-check` (rejected: brittle compiler flag).

### 2. Spike-First Migration Strategy
- **Decision**: Phase 1 will perform an isolated compatibility spike verifying that a minimal Kotlin source file can instantiate LiteRT-LM's `EngineConfig`, `Engine`, `Conversation`, and collect `Flow<Message>` before any destructive deletion of legacy code.
- **Rationale**: Ensures the toolchain matrix is 100% verified and reproducible before altering application code, making the migration safe and reversible.

### 3. Pure-Kotlin Single Runtime Architecture & Runtime Lifecycle
- **Decision**: `LiteRtLlmEngine.kt` implements `LlmEngine` directly and is wired as a `@Singleton` in `AppModule`.
- **Engine vs. Conversation Lifecycle**:
  - `Engine`: Heavyweight model instance. Initialized once when the model is loaded/selected. Stays active across multiple chat turns. Released when model is unloaded or switched.
  - `Conversation`: Lightweight session instance created from `Engine`. Manages turn-by-turn dialogue and message context. Closed when conversation resets.
  - Lifecycle flow:
    ```
    load model
        ↓
    create EngineConfig (GPU preferred)
        ↓
    initialize Engine once
        ↓
    create Conversation
        ↓
    send multiple messages via Conversation.sendMessageAsync() -> Flow<Message>
        ↓
    close Conversation on conversation reset
        ↓
    release Engine on model unload / switch
    ```

### 4. GPU-Preferred & Isolated CPU Fallback Policy
- **Decision**: GPU backend is tried first. If initialization fails, the error is classified via a dedicated, isolated classifier:
  1. *Genuine backend/device failure* (GPU driver unavailable, OpenCL error, out-of-GPU-memory during backend init) $\rightarrow$ Retry initialization on CPU backend.
  2. *Model/artifact failure* (missing tokenizer, missing tensor section, invalid `.litertlm` binary structure) $\rightarrow$ Fail immediately, preserve root-cause exception, do NOT retry on CPU.
- **Implementation**: Inspect typed LiteRT-LM exceptions/error codes first; any necessary error inspection is encapsulated strictly within an internal classifier function.

### 5. Native Cancellation
- **Decision**: In addition to cancelling the Kotlin coroutine collector, `LiteRtLlmEngine` explicitly invokes LiteRT-LM's native cancellation mechanism (`Conversation.cancelProcess()`) to guarantee native inference is immediately halted without lingering background threads or resource leaks.

### 6. Strict Separation of Model Management and Runtime
- **Decision**: `ModelManager`, `ModelRegistry`, and `ModelStorage` manage model files, paths, and JSON metadata on disk. They hold NO references to `Engine`, `Conversation`, or native handles. `LiteRtLlmEngine` owns the live runtime state.

### 7. Layered Model Validation
- **Decision**: Model validation follows a staged pipeline:
  1. File existence & basic readability.
  2. File extension check (`.litertlm`).
  3. LiteRT-LM supported validation/pre-check APIs (or lightweight engine init if no separate validation API exists).
- **Rationale**: Avoids inventing speculative binary magic parsers while preventing invalid models from being marked as active.

## Risks / Trade-offs

- **[Toolchain Update Incompatibilities]** → Updating Kotlin/AGP/KSP might impact other dependencies (e.g. Hilt).
  *Mitigation*: Establish an exact, verified version matrix during the Phase 1 spike.
- **[Device GPU Driver Discrepancies]** → Some Android devices fail unexpectedly on Vulkan/OpenCL.
  *Mitigation*: Tested and isolated CPU fallback ensures inference works across diverse Android chipsets.
- **[LiteRT-LM Exception Granularity]** → The SDK may throw generic native exceptions rather than granular subclasses.
  *Mitigation*: Isolate error inspection in one documented compatibility function to distinguish backend init errors from model structure errors.

## Migration Plan

1. **Phase 0**: Record baseline toolchain versions and FIR compiler error.
2. **Phase 1**: Inspect LiteRT-LM release, establish exact version matrix in `libs.versions.toml`, and execute Kotlin compatibility spike.
3. **Phase 2**: Implement pure-Kotlin `LiteRtLlmEngine.kt` with explicit Engine/Conversation lifecycle, native cancellation, and isolated fallback.
4. **Phase 3**: Remove legacy llama.cpp (sources, CMake, NDK, bridges, `DelegatingLlmEngine`, MediaPipe).
5. **Phase 4**: Refactor `ModelManager`, `ModelRegistry`, `ModelRecord`, and validation to `.litertlm` only (keeping runtime state separate).
6. **Phase 5**: Update DI (`AppModule.kt`), UI (`ModelLibraryScreen.kt`, `MainActivity.kt`), and remove multi-runtime selectors.
7. **Phase 6**: Run unit tests, dependency graph verification, Java/workaround regression checks, and verify on real Android target with a known-good `.litertlm` fixture.
