## Context

The Android repository currently has a singleton `ModelManager` in `core:llm` that searches app storage for `model.gguf` and `model.bin`. `app` selects `LlmEngine` implementations in Hilt using those filename heuristics, while `MainActivity` switches between Compose screens with an `AppScreen` enum. The app already uses Kotlin serialization and Preferences DataStore, has `minSdk = 29`, and declares no broad storage permission.

Change 1 must establish model identity and storage without redesigning inference. LiteRT-LM is the primary runtime, llama.cpp/GGUF is secondary, and a later engine-capabilities change will consume the selected model's runtime and format. The current MediaPipe API/runtime evidence does not establish a portable byte-level detector for every LiteRT-LM artifact, so validation must report only what can be proven.

## Goals / Non-Goals

**Goals:**

- Store multiple model records in a durable JSON manifest.
- Keep artifacts in Loki-managed app-specific storage and handle large files as streams.
- Support local SAF import, curated remote catalog plus bundled fallback, and artifact downloads.
- Represent runtime, format, lifecycle, source, size, and integrity metadata explicitly.
- Validate before registration, adopt accessible legacy models safely, and keep active-model state consistent.
- Provide explicit load, switch, eject/unload, and delete operations with only one loaded model initially.
- Add a minimal Compose Model Library connected through existing screen switching.
- Leave a clean model-selection boundary for future engine migration.

**Non-Goals:**

- Replacing `LlmEngine` with persistent sessions or adding capability negotiation.
- Implementing grammar enforcement, prompt-level structured-output retries, agent configuration, or pipeline observability.
- Implementing chat/voice memory policy, keyboard fixes, or tool semantic changes.
- Assuming `.bin` means Gemma or that every LiteRT-LM model is Gemma.
- Introducing Room or a navigation framework solely for this feature.
- Making another application's private storage accessible.

## Decisions

### Model identity is explicit

`ModelRecord` will use a stable generated ID and include display name, family/model identity when known, runtime, format, managed artifact path, byte size, source, source URL or origin metadata, optional SHA-256, capabilities, lifecycle/availability state, timestamps, and confidence or provenance for metadata where useful. Runtime/format, model identity, display name, size, checksum, and capabilities are separate fields. Filename is a hint only, except for explicitly documented legacy adoption conventions.

For arbitrary local imports, metadata collection follows detection and validation: inspect the selected file, determine what runtime/format information can be proven, validate through the appropriate runtime, then ask the user only for fields that remain unknown and allow review/editing before registration. The system must never treat a filename as authoritative identity.

### Registry is a JSON manifest with atomic persistence

A serializable registry repository will read and write a manifest under Loki-managed storage. Writes use a temporary manifest and atomic replacement where Android filesystem behavior permits; malformed or unreadable manifests are surfaced as an actionable recovery state rather than silently creating a different active model. The registry stores the active model ID separately or in the manifest and never treats file existence alone as selection.

DataStore remains appropriate for small preferences such as a selected ID if the implementation needs it, but model records and per-model metadata belong in the JSON manifest. Room is not introduced.

### Managed storage is per model

Use app-specific external files storage where suitable, with an internal fallback for the registry and metadata. Each model gets an ID-scoped directory containing its finalized artifact and metadata. Imports and downloads first write to a `.part` file, stream bytes without loading the artifact into memory, verify expected size/checksum when available, validate the artifact, and then finalize with an atomic rename. Incomplete files are never registered as usable models.

### Runtime detection and validation are layered

The detector may reliably identify GGUF using repository-supported GGUF metadata/parsing and may use extension only as a candidate hint. LiteRT-LM detection must not invent a header, byte signature, or metadata-only validation API. The design distinguishes file/format validation, runtime compatibility validation, and model identity metadata.

`ModelValidator` has runtime-specific implementations such as `GgufModelValidator` and `LiteRtModelValidator`. `LiteRtModelValidator` must use only APIs actually available in the pinned MediaPipe `tasks-genai 0.10.35` dependency. Because that API does not provide a simple standalone validation call, the validator may create a temporary `LlmInference` instance to attempt initialization, report success/failure, and immediately close it; the temporary instance is never retained as the active engine. If the runtime cannot establish a piece of metadata reliably, that field remains unknown and is requested from the user where needed. Validation failure prevents registration as usable.

### Import uses SAF

The UI launches `ACTION_OPEN_DOCUMENT` (or the equivalent Activity Result contract) and copies the selected content URI into managed storage. The application does not retain arbitrary external paths as canonical runtime paths and does not request broad storage access. URI access and copy failures leave no registered model and clean temporary files.

### Catalog and downloads are replaceable

A small remote catalog has a versioned, serializable schema containing model ID, metadata, artifact URL, expected size, and optional checksum. A bundled catalog with known-good entries is used when the remote catalog is unavailable or invalid. Download transport is an implementation detail: it must provide streaming, progress, cancellation/failure cleanup, and atomic finalization, but this change does not mandate OkHttp, `HttpURLConnection`, or another client.

The first implementation deliberately does not require HTTP Range-based resumable downloads. A failed or interrupted transfer may leave a temporary `.part` file only if it is safely ignored or replaced on the next attempt; it must never appear as a downloaded model. Resume support can be added later without changing the model-library contract.

### Lifecycle is serialized

Model operations are coordinated so load, switch, eject, and delete cannot race. Loading a model first unloads the current model through a model-runtime boundary, then updates the active model only after successful validation/load. If the new load fails, registry state describes the actual loaded model and does not claim the failed model is active. Eject unloads runtime resources but retains the record and artifact; delete requires an explicit action and unloads first when deleting the loaded model.

### Legacy adoption is conservative

Migration scans only application-owned legacy locations that are accessible at runtime, such as the current app's internal/external `model.bin` and `model.gguf` paths. The known `gemma-2b-it-cpu-int4.bin` filename is only a hint and is not proof of model identity or format. A legacy artifact is copied or moved into managed storage only after detection, runtime validation, and metadata review/collection. The old `com.example.loki` sandbox is not accessed and no privileged access is added. Shared-storage files are handled through SAF import.

### UI integrates with existing routing

Add `MODEL_LIBRARY` to `AppScreen`, expose the screen from the Chat model status control as the primary entry point, and allow Setup to direct users there when no usable active model exists. The screen renders records and explicit actions for import, download, load/switch, eject, and delete, including progress and error states. No navigation dependency is added.

## Risks / Trade-offs

- **LiteRT-LM metadata may be insufficient for automatic identification** → use a temporary runtime initialization test based only on the pinned API, preserve unknown fields, and request user confirmation; never claim a format or identity was detected when it was not.
- **A multi-gigabyte copy can fail or exhaust storage** → stream data, check available space where practical, use `.part` files, clean failures, and report actionable errors.
- **Manifest corruption can orphan files or lose selection** → atomic writes, tolerant read diagnostics, and reconciliation of managed directories during initialization.
- **Switching can leave two runtimes allocated** → serialize lifecycle operations and unload before loading the next model; persist active ID only after successful load.
- **Catalog metadata or checksums can become stale** → validate catalog schema and artifact integrity before registration; fall back to bundled entries.
- **A failed download may leave a partial file** → use a `.part` file that is ignored by the registry, clean it up where practical, and replace or remove it before a later attempt; defer HTTP Range resume.
- **Current engines still accept paths and contain discovery fallbacks** → retain compatibility adapters in Change 1 and document the remaining engine migration for Change 2; do not duplicate a second engine redesign here.
- **Legacy migration may find no accessible artifact** → treat migration as best-effort and direct users to SAF import rather than assuming access to another package.

## Migration Plan

1. Initialize the registry and managed model directory on app startup or first Model Library access.
2. Reconcile existing managed records and run a one-time, idempotent scan of accessible legacy locations.
3. Validate and adopt eligible legacy artifacts; preserve unrecognized files and expose them for SAF import when applicable.
4. Resolve the active model by stored model ID. If no active record is usable, present Model Library instead of selecting by extension.
5. Keep a narrow compatibility path for current engine construction until Change 2 consumes `ModelRecord` directly.
6. On rollback, existing legacy files remain untouched; managed records/artifacts can be removed independently, and the old filename lookup remains available only as a temporary compatibility fallback.

## Resolved Implementation Constraints

- Arbitrary local imports perform detection and runtime validation before requesting only unknown metadata; the user can review and edit the resulting fields before registration.
- LiteRT-LM validation uses only verified `tasks-genai 0.10.35` APIs. A temporary `LlmInference` initialization/load test is acceptable when required, and it must be closed immediately rather than retained as the active engine.
- The first implementation uses streaming downloads, progress, optional SHA-256 verification, `.part` files, atomic finalization, and reliable failure cleanup. HTTP Range-based resumability is deferred as a later enhancement.
- Legacy adoption is limited to accessible current-app storage and SAF-imported shared-storage files; the known Gemma filename and another package's private path are never treated as authoritative or accessible.
