# Proposal: Centralized Model Management System

## Overview
This proposal evolves Loki's model management from an LLM-centric library into a unified, cross-runtime infrastructure. It enables the dynamic discovery, download, and lifecycle management of models for LLM, ASR (Whisper), and Custom TTS, ensuring no large models are bundled in the APK.

## Problem Statement
Current model management is coupled to the `:core:llm` module and assumes a single globally active model. This prevents Loki from running an LLM, a Whisper STT model, and a custom TTS model simultaneously, and forces runtimes to use hardcoded or independent model-finding logic.

## Proposed Architecture

### 1. New Module: `:core:models`
Extract common model management logic into a shared module.
- **Data Models**: `ModelRecord`, `ModelCatalogEntry`, `ModelRuntime`, `ModelFormat`.
- **Registry**: `ModelRegistry` (JSON-based persistence).
- **Storage**: `ModelStorage` (Directory management, multi-artifact support).
- **Downloader**: `ModelDownloader` & `ModelTransfer` (Hugging Face / HTTPS streaming with multi-file support).

### 2. Multi-Active Runtime State
Update `ModelManifest` to track active models per runtime:
```kotlin
data class ModelManifest(
    val schemaVersion: Int,
    val activeModels: Map<ModelRuntime, String>, // Map<Runtime, ModelId>
    val models: List<ModelRecord>
)
```

### 3. Unified Model Library Manager
Refactor `ModelLibraryManager` to:
- Manage a registry of `ModelRuntimeController`s.
- Handle `load(modelId)` by identifying the runtime and only unloading the previously active model *for that specific runtime*.
- Expose readiness states for the `SetupScreen`.
- Own the lifecycle and state (Downloaded, Loaded, Ejected).

### 4. LiteRT Whisper Integration
- Retire legacy `WhisperSttEngine` (whisper.cpp).
- Update `LiteRtWhisperEngine` to implement `ModelRuntimeController`.
- Ensure it loads models exclusively from the `ModelStorage` path provided by the registry.

### 5. Multi-Artifact Model Packages
`ModelRecord` and `ModelCatalogEntry` will represent models as a collection of artifacts (e.g., `.tflite`, `tokenizer.json`, `config.json`) rather than a single file. This is critical for LiteRT-LM (Gemma) and future runtimes.

### 6. Provisioning Flow (Setup)
- `SetupScreen` becomes the gatekeeper for mandatory models.
- Required: `ModelRuntime.LITERT_LM` and `ModelRuntime.LITERT_ASR`.
- If missing, the user is seamlessly guided through the download process using the shared `ModelDownloader`.
- Setup is only "Complete" once both mandatory runtimes are LOADED.

## User Experience Improvements
- **Model Categories**: UI clearly separates models by their role (Chat, Voice Recognition, Voice Synthesis).
- **Parallel Downloads**: Support for downloading multiple models in the background.
- **Detailed State**: Distinguish between "Downloaded" (on disk) and "Loaded" (active in memory).

## Verification Plan
- **Unit Tests**: Update `ModelRegistryTest` for multi-active map logic.
- **Integration Tests**: Verify `LiteRtWhisperEngine` loads a downloaded `.tflite` model from the new storage layout.
- **Manual**: Run full Setup flow on a fresh install to verify no bundled models are required.
