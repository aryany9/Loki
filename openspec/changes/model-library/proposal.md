## Why

Loki currently discovers `model.bin` and `model.gguf` by filename and selects an engine from file-presence heuristics. That prevents multiple models, safe switching, reliable import/download, and honest runtime identity; a model library is needed before LiteRT-LM can remain the primary runtime while llama.cpp remains an extensible secondary backend.

## What Changes

- Introduce a first-class `ModelRecord` containing stable identity, display metadata, runtime/format, managed artifact location, source, size, integrity data, and lifecycle state.
- Replace filename discovery as the primary registry with a persistent JSON manifest and managed per-model storage.
- Support one active loaded model, explicit load/switch/eject/unload, and explicit deletion. Eject preserves the stored artifact; delete removes the artifact and registry entry.
- Add SAF-based local model import that copies files into managed storage, detects and validates only what the repository/runtime can prove, and requests user-confirmed metadata when identity is uncertain.
- Add streaming model downloads for curated Hugging Face artifacts and supported direct artifact URLs, with remote catalog plus bundled fallback, progress, temporary `.part` files, checksum verification where available, atomic finalization, and failure cleanup.
- Add safe adoption of accessible legacy model locations without assuming access to another application’s sandbox.
- Add a minimal Compose Model Library screen, opened primarily from Chat and optionally surfaced by Setup when no usable model exists, using the existing `AppScreen` routing pattern.
- Add model registry, storage, import/download state, migration, validation, and lifecycle tests.
- Establish the model identity and manager boundary that a later engine-capability change can use to select `LiteRtLlmEngine` or `LlamaCppLlmEngine`; do not redesign `LlmEngine`, persistent sessions, structured output, or agent behavior here.

## Capabilities

### New Capabilities

- `model-library`: Persistent multi-model registration, managed storage, runtime/format identity, import and download workflows, validation, migration, active-model selection, lifecycle operations, and the Model Library UI.

### Modified Capabilities

None. Existing engine requirements, including backend capability behavior, are intentionally deferred to the later engine-capabilities change. This change provides compatibility seams without implementing that redesign.

## Impact

- `core:llm`: Replace the current filename-based `ModelManager` responsibility with model records, registry/storage/import/download/validation abstractions, while preserving current engine compatibility during migration.
- `app`: Update Hilt wiring and simple `AppScreen` routing; connect the existing Chat model status entry point and Setup no-model state.
- `core:ui`: Add Model Library Compose UI and state handling.
- Persistence: Add a JSON manifest in Loki-managed app storage, using existing Kotlin serialization/DataStore-related infrastructure as appropriate; Room is not required.
- Storage: Use app-specific managed storage and Android SAF; no broad storage permission is required.
- Networking: Add only the lightweight mechanism required for streaming downloads; the proposal does not mandate a specific HTTP library.
- Existing legacy artifacts: Adopt accessible `model.bin`/`model.gguf` files into the registry; shared-storage copies require SAF, and another package’s private directory is not assumed accessible.
