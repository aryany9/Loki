# Centralized Model Management Research

## 1. Reusable Classes
- `ModelRecord`, `ModelCatalogEntry`, `ModelRuntime`, `ModelFormat`, `ModelSource`, `ModelAvailability`, `ModelManifest`: These are the data foundations. They are mostly ready but need to be moved to a shared module.
- `ModelRegistry`, `ModelStorage`, `ModelDownloader`, `ModelTransfer`: These are functional core components. They are well-implemented (e.g., supporting `.part` files, SHA-256 validation) and should be preserved.
- `ModelLibraryManager`: Needs refactoring to handle multi-runtime states, but the logic for register/download/delete is reusable.

## 2. Shared Model-Management Module
- Current location: `core:llm`.
- New location: `core:models`.
- Contents: All classes in `dev.loki.android.core.llm` that are not specific to LLM execution (Registry, Storage, Catalog, Downloader, Types).

## 3. Representation of LLM, ASR, and TTS in `ModelRecord`
- `ModelRuntime` enum already has `LITERT_LM` and `LITERT_ASR`.
- Action: Add `LITERT_TTS` or `CUSTOM_TTS` to `ModelRuntime`.
- `ModelManifest` needs to track `activeModels: Map<ModelRuntime, String>` instead of a single `activeModelId: String?`.

## 4. Runtime-Specific Loading
- `ModelLibraryManager` currently uses a single `ModelRuntimeController`.
- Action: Transition to a `Map<ModelRuntime, ModelRuntimeController>` or a registry of controllers.
- When `load(modelId)` is called, the manager identifies the `ModelRuntime` from the `ModelRecord` and delegates to the appropriate controller.

## 5. Simultaneous Multi-Runtime Loading
- By updating `ModelManifest` to use a Map for active models and ensuring `ModelLibraryManager` doesn't `unload` a model of a different runtime when loading a new one, we can support simultaneous loading.
- `ModelLibraryManager.load(modelId)` should only `unload` models of the *same* `ModelRuntime`.

## 6. Setup Readiness Determination
- `SetupScreen` currently checks permissions.
- Action: Update to check `ModelLibraryManager.isRuntimeReady(runtime)`.
- Readiness criteria: A model for that runtime is `DOWNLOADED` and ideally `LOADED`.

## 7. Model Library and Setup State Sharing
- Both will consume `ModelLibraryManager.manifest` (a `StateFlow`).
- This ensures the UI is always in sync with the registry.

## 8. Retirement of Whisper.cpp
- `WhisperSttEngine` and `WhisperModelManager` are hardcoded for legacy files (`whisper.bin`).
- Action: Delete these. Move any relevant VAD/Audio logic to a shared `core:voice:stt` utility if not already present.

## 9. `LiteRtWhisperEngine` Integration
- Current state: Mocked/Incomplete.
- Action: Implement `ModelRuntimeController` in `LiteRtWhisperEngine`.
- It will receive the `ModelRecord` and load the `.tflite` file into the LiteRT interpreter.

## 10. Custom TTS Integration
- `AndroidTtsEngine` remains as is (no model management needed).
- `CustomTtsEngine` will implement `ModelRuntimeController` and integrate with the Model Library like Whisper/LLM.

## 11. Test Updates
- `ModelRegistryTest`, `ModelLibraryManagerTest`: Update to handle multi-active models and shared storage.
- New tests: `ModelDownloader` integration for Whisper/TTS models, `SetupScreen` readiness logic.
