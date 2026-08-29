# Tasks: Centralized Model Management Implementation

## Phase 1: Infrastructure (Shared Module)
- [ ] Create `:core:models` module.
- [ ] Refactor `ModelRecord` and `ModelCatalogEntry` to support `List<ModelArtifact>`.
- [ ] Move and update `ModelRegistry`, `ModelStorage`, `ModelDownloader`, and `ModelTransfer` to handle multi-artifact downloads and integrity checks.
- [ ] Refactor `ModelManifest` to use `activeModels: Map<ModelRuntime, String>`.
- [ ] Update `ModelRegistry.reconcile()` to handle the multi-active map and legacy migration.

## Phase 2: Manager & Runtimes
- [ ] Refactor `ModelLibraryManager` to support multiple `ModelRuntimeController`s and own the loaded/active state.
- [ ] Implement `isRuntimeReady(runtime: ModelRuntime): Boolean` (is DOWNLOADED and LOADED).
- [ ] Update `LiteRtLlmEngine` to register as a `LITERT_LM` controller.
- [ ] Retire `WhisperSttEngine` and `WhisperModelManager`.
- [ ] Refactor `LiteRtWhisperEngine` to implement `ModelRuntimeController` for `LITERT_ASR` and load real `.tflite` model.

## Phase 3: UI & UX
- [ ] Update `ModelLibraryScreen` to display models categorized by `ModelRuntime` and support multi-artifact visibility.
- [ ] Evolve `SetupScreen` to provision and load BOTH mandatory LLM and ASR models.
- [ ] Ensure `AssistantSession` checks `ModelLibraryManager` for readiness before starting turns.

## Phase 4: Validation
- [ ] Verify simultaneous LLM + ASR loaded state in integration tests.
- [ ] Confirm no model files are bundled in the APK.
- [ ] Test Hugging Face multi-artifact download (e.g. model + tokenizer).
