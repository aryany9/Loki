# Implementation Plan: Centralized Model Management System

Evolve Loki's model management into a centralized system for LLM and LiteRT ASR runtimes, supporting multi-artifact downloads from Hugging Face and simultaneous runtime execution.

## User Review Required

> [!IMPORTANT]
> This change introduces a new module `:core:models`. Ensure the project's `settings.gradle.kts` is updated accordingly.

> [!WARNING]
> Existing models will be migrated to the new `activeModels` map format in the registry. The single `activeModelId` field will be deprecated.

## Proposed Changes

### Core Models Module [NEW]

Create a new `:core:models` library module to host the shared model management logic.

#### [NEW] [ModelTypes.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/models/src/main/java/dev/loki/android/core/models/ModelTypes.kt)
- Define `ModelRuntime` (LITERT_LM, LITERT_ASR, CUSTOM_TTS).
- Define `ModelArtifact` (fileName, relativePath, size, sha256, url).
- Update `ModelRecord` and `ModelCatalogEntry` to use `List<ModelArtifact>`.
- Update `ModelManifest` to use `activeModels: Map<ModelRuntime, String>`.

#### [NEW] [ModelStorage.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/models/src/main/java/dev/loki/android/core/models/ModelStorage.kt)
- Move and adapt `ModelStorage` from `:core:llm`.
- Update to support directory-based storage for multi-artifact models.

#### [NEW] [ModelRegistry.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/models/src/main/java/dev/loki/android/core/models/ModelRegistry.kt)
- Move and adapt `ModelRegistry` from `:core:llm`.
- Implement migration from `activeModelId` to `activeModels` map.
- Handle multi-runtime active states.

#### [NEW] [ModelDownloader.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/models/src/main/java/dev/loki/android/core/models/ModelDownloader.kt)
- Move and adapt `ModelDownloader` and `ModelTransfer`.
- Update `download()` to iterate through and validate all artifacts in a package.

---

### Model Management & Runtimes

#### [MODIFY] [ModelLibraryManager.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/ModelLibraryManager.kt)
- Refactor to handle multiple `ModelRuntimeController`s.
- Manage simultaneous `LOADED` states per runtime.
- Implement `isRuntimeReady(runtime)` (DOWNLOADED + LOADED).

#### [MODIFY] [LiteRtLlmEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/llm/src/main/java/dev/loki/android/core/llm/LiteRtLlmEngine.kt)
- Register as `LITERT_LM` controller.

#### [MODIFY] [LiteRtWhisperEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/voice/stt/src/main/java/dev/loki/android/core/voice/stt/LiteRtWhisperEngine.kt)
- Register as `LITERT_ASR` controller.
- Replace mock transcription with actual LiteRT `.tflite` loading from `ModelRecord`.

#### [DELETE] [WhisperSttEngine.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/voice/stt/src/main/java/dev/loki/android/core/voice/stt/WhisperSttEngine.kt)
- Retire legacy whisper.cpp engine.

---

### UI & UX Integration

#### [MODIFY] [SetupScreen.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/ui/src/main/java/dev/loki/android/core/ui/SetupScreen.kt)
- Update provisioning flow to ensure both LLM and ASR models are downloaded and loaded.

#### [MODIFY] [ModelLibraryScreen.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/ui/src/main/java/dev/loki/android/core/ui/ModelLibraryScreen.kt)
- Update UI to categorize models by runtime and display multi-artifact details.

#### [MODIFY] [AssistantSession.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/assistant/src/main/java/dev/loki/android/core/assistant/AssistantSession.kt)
- Add guard check for `ModelLibraryManager.isRuntimeReady()` for both LLM and ASR before starting a turn.

## Verification Plan

### Automated Tests
- `ModelRegistryTest`: Verify `activeModels` migration and simultaneous active IDs.
- `ModelDownloaderTest`: Mock multi-artifact download and SHA validation.
- `ModelLibraryManagerTest`: Verify `load()` behavior for different runtimes without cross-unloading.

### Manual Verification
1.  Perform a clean install.
2.  Complete the `SetupScreen` flow, verifying both LLM and ASR are downloaded/loaded.
3.  Navigate to `ModelLibraryScreen` and verify both runtimes show "LOADED".
4.  Trigger a voice interaction to confirm `AssistantSession` initializes correctly with the dynamic models.
5.  Verify no model files are present in the APK assets.
