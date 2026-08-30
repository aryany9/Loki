## 1. Model Registry and Domain Types

- [x] 1.1 Define serializable `ModelRecord`, runtime/format/source/state types, stable model ID rules, and catalog entry types in `core:llm`.
- [x] 1.2 Implement a JSON manifest repository with atomic writes, active-model ID persistence, malformed-manifest diagnostics, and record reconciliation.
- [x] 1.3 Add unit tests for serialization, round trips, duplicate IDs, malformed manifests, missing artifacts, and active-model restoration.

## 2. Managed Storage and Validation

- [x] 2.1 Implement ID-scoped managed model directories and artifact/metadata paths using app-owned storage without broad storage permissions.
- [x] 2.2 Implement streaming file copy, temporary `.part` handling, available-space/error reporting, cleanup, and atomic finalization.
- [x] 2.3 Define detector and validator interfaces, implement only repository/runtime-proven format checks, and provide an explicit metadata-confirmation path for unknown artifacts.
- [x] 2.4 Add checksum and size verification utilities that stream SHA-256 computation and never load a complete model into memory.
- [x] 2.5 Add unit tests for storage paths, large-file streaming behavior, checksum success/failure, unsupported/unknown formats, and validation rejection.

## 3. Import and Download Workflows

- [x] 3.1 Add SAF document selection and import orchestration that copies a user-selected URI into managed storage before validation and registration.
- [x] 3.2 Define a versioned catalog schema and bundled fallback catalog with explicit LiteRT-LM/GGUF metadata, URLs, sizes, and optional checksums.
- [x] 3.3 Implement remote catalog retrieval with schema validation and fallback to the bundled catalog on network or parsing failure.
- [x] 3.4 Implement streaming catalog/direct-artifact downloads with progress, cancellation, `.part` cleanup, integrity checks, and atomic installation.

## 4. Lifecycle, Active Model, and Legacy Migration

- [x] 4.1 Implement serialized model operations for register, load, switch, eject/unload, and explicit delete, allowing only one loaded model initially.
- [x] 4.2 Ensure eject retains the record and artifact, delete removes both explicitly, and failed switches leave active/loaded state consistent.
- [x] 4.3 Add idempotent migration for accessible current-app legacy `model.bin` and `model.gguf` locations, using filename metadata only for documented legacy hints.
- [x] 4.4 Ensure migration never accesses another package’s private storage and routes shared-storage copies through SAF.
- [x] 4.5 Add lifecycle and migration tests covering repeated migration, valid/invalid legacy files, eject versus delete, switching failures, and no simultaneous loaded models.

## 5. Engine Compatibility Boundary

- [x] 5.1 Update model-manager and Hilt integration so model identity/runtime metadata is available to future engine selection while retaining the smallest necessary compatibility path for current engines.
- [x] 5.2 Remove model-library decisions that depend on extension-only discovery as the primary selection mechanism, without implementing persistent sessions, capability negotiation, or structured-output redesign.
- [x] 5.3 Add focused tests proving the selected `ModelRecord` is the source of runtime/format selection data and that no-model/error states are explicit.

## 6. Model Library UI and App Wiring

- [x] 6.1 Add `MODEL_LIBRARY` to the existing `AppScreen` routing and provide a primary entry point from the Chat model status control.
- [x] 6.2 Implement Model Library state collection and Compose UI for installed/catalog models, runtime/format metadata, active state, and lifecycle actions.
- [x] 6.3 Add SAF import and download progress/error presentation, metadata confirmation for uncertain imports, and explicit delete confirmation.
- [x] 6.4 Direct Setup or the initial app state to Model Library when no usable model exists, without changing chat/voice memory behavior.
- [x] 6.5 Add Compose/UI tests for entry, empty state, model list, load/eject/delete actions, progress, and actionable failures.

## 7. Verification and Documentation

- [x] 7.1 Document managed storage layout, manifest schema/versioning, lifecycle semantics, catalog fallback, checksum behavior, and known LiteRT-LM identification limitations.
- [x] 7.2 Run core model-library unit tests and Android/UI tests, including migration and failure-path coverage.
- [x] 7.3 Run `./gradlew :core:llm:test` and `./gradlew assembleDebug`, then resolve only failures caused by this change.
