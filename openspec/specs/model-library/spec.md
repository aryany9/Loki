# model-library Specification

## Purpose
TBD - created by archiving change litertlm-runtime-migration. Update Purpose after archive.
## Requirements
### Requirement: The system maintains a persistent model registry
The system SHALL represent every installed model as a first-class `ModelRecord` in a persistent JSON manifest. A record MUST include a stable ID, display metadata, managed artifact location, source/origin, size, lifecycle state, and optional integrity metadata. All registered LLM models SHALL use the `.litertlm` model format.

#### Scenario: Registry survives process restart
- **WHEN** the application restarts after a `.litertlm` model has been registered
- **THEN** the model record and active model ID are restored from managed persistence
- **AND** a missing or invalid artifact is reported as unavailable rather than selected as usable

### Requirement: Model identification and validation are evidence-based
The system SHALL validate `.litertlm` models using a layered, evidence-based approach: basic file validation (existence, readability, `.litertlm` extension), followed by LiteRT-LM supported validation/initialization checks. It MUST NOT use speculative heuristic binary parsers. A failed validation MUST prevent registration as a usable model.

#### Scenario: Valid .litertlm artifact imported
- **WHEN** the user imports a valid `.litertlm` model file via Storage Access Framework
- **THEN** the system copies it to managed storage, validates it against LiteRT-LM validation APIs, and registers it as `DOWNLOADED`

#### Scenario: Incompatible or corrupt artifact rejected
- **WHEN** a selected file has a `.litertlm` extension but fails LiteRT-LM validation checks
- **THEN** the import operation fails with an actionable error message
- **AND** the artifact is removed from storage without creating an active or usable registry entry

### Requirement: Existing accessible legacy models are adopted safely
The system SHALL perform an idempotent migration check for accessible application-owned legacy model directories. It SHALL adopt accessible `.litertlm` files into the registry. It MUST NOT adopt obsolete GGUF or `.bin` files into active runtime service.

#### Scenario: Accessible .litertlm model adopted
- **WHEN** migration finds an existing valid `.litertlm` file in an accessible application directory
- **THEN** Loki adopts it into managed model storage and registers it

### Requirement: Model records carry capability metadata
The system SHALL store per-model capability metadata on the `ModelRecord` (including audio-input support) with a confidence level. Capability values SHALL be populated from structural container inspection (`LitertLmContainerInspector`) as VERIFIED, from bundled catalog entries as VERIFIED, and from an explicit user toggle at import time as USER_CONFIRMED. When a `.litertlm` artifact contains audio encoder/adapter section markers (`tf_lite_audio_encoder_hw`, `tf_lite_audio_adapter`) in its header, the system SHALL detect and assign audio-input capability without relying on filename heuristics.

#### Scenario: Direct container inspection detects audio component sections
- **WHEN** a `.litertlm` model file containing `tf_lite_audio_encoder_hw` or `tf_lite_audio_adapter` sections is inspected or reconciled
- **THEN** the model record carries audio-input capability with VERIFIED confidence

#### Scenario: Catalog entry with audio capability is downloaded
- **WHEN** a bundled catalog entry declares audio-input capability and the user downloads it
- **THEN** the registered model record carries audio-input capability with VERIFIED confidence

#### Scenario: Imported model with user-declared audio support
- **WHEN** the user imports a model and enables the audio-support toggle
- **THEN** the registered model record carries audio-input capability with USER_CONFIRMED confidence
- **AND** the toggle defaults to false when untouched

#### Scenario: Record without capability signal is treated as text-only
- **WHEN** a model record has no confirmed audio-input capability and no structural audio sections
- **THEN** the voice pipeline treats the model as text-only for strategy selection

---

### Requirement: Model artifacts use Loki-managed storage
The system SHALL copy imported and downloaded artifacts into an ID-scoped Loki-managed application directory. It MUST NOT depend permanently on an arbitrary SAF source path. Artifact transfer SHALL stream data and SHALL NOT require the complete model in memory.

#### Scenario: Local model import is copied
- **WHEN** the user selects a model through the Android Storage Access Framework
- **THEN** Loki copies it into managed model storage
- **AND** the registry references the managed artifact rather than the original arbitrary URI

#### Scenario: Interrupted transfer is not installed
- **WHEN** an import or download fails before completion
- **THEN** the temporary `.part` artifact is removed or retained only as an explicitly resumable temporary file
- **AND** no usable `ModelRecord` is created for the incomplete artifact

---

### Requirement: The catalog has a remote source and bundled fallback
The system SHALL support a small versioned remote curated catalog of downloadable model artifacts and a bundled fallback catalog. The fallback SHALL be used when the remote catalog is unavailable or invalid. Catalog entries MUST provide enough metadata to identify and validate the expected artifact, including URL, expected size, and checksum when supplied by the source.

#### Scenario: Remote catalog is available
- **WHEN** Loki retrieves a valid remote catalog
- **THEN** the Model Library displays its supported downloadable entries
- **AND** each entry retains its declared runtime, format, source, and integrity metadata

#### Scenario: Remote catalog is unavailable
- **WHEN** the remote catalog cannot be fetched or fails schema validation
- **THEN** Loki uses the bundled catalog
- **AND** the user can still view and download bundled fallback entries

---

### Requirement: Downloads are integrity-checked and atomically finalized
The system SHALL download model artifacts as streams into temporary files, report progress when the source provides a length, verify expected size and SHA-256 when available, validate the artifact before installation, and atomically finalize the managed artifact. A failed verification SHALL NOT produce an installed usable record.

#### Scenario: Verified download is installed
- **WHEN** a catalog artifact downloads successfully and all available integrity and runtime validation checks pass
- **THEN** Loki atomically finalizes the artifact in managed storage
- **AND** registers it as `DOWNLOADED` without claiming it is loaded

#### Scenario: Checksum mismatch is rejected
- **WHEN** a downloaded artifact's SHA-256 differs from the catalog checksum
- **THEN** Loki rejects the artifact
- **AND** removes the finalized or temporary invalid file
- **AND** leaves no usable registry entry for that download

---

### Requirement: Model lifecycle distinguishes loading, ejecting, and deleting
The system SHALL support `NOT_DOWNLOADED`, `DOWNLOADED`, and `LOADED` states. Loading a model SHALL unload any previously active model of the same runtime before loading the selected one. Eject SHALL unload runtime resources while retaining the model record and artifact. Delete SHALL be an explicit operation that removes the artifact and registry entry, and SHALL unload a loaded model before removal.

#### Scenario: Model is ejected
- **WHEN** the user ejects the currently loaded model
- **THEN** Loki releases the model runtime resources
- **AND** the model remains registered with its artifact on disk in a downloaded/unloaded state

#### Scenario: Model is switched
- **WHEN** the user loads a different downloaded model of the same runtime
- **THEN** Loki unloads the current model before loading the selected model
- **AND** updates the active model only after the new model loads successfully

#### Scenario: Loaded model is deleted explicitly
- **WHEN** the user confirms deletion of the loaded model
- **THEN** Loki unloads it, removes its artifact and registry entry, and clears or updates the active model
- **AND** deletion is never triggered by ejecting or switching alone

---

### Requirement: The Model Library is available through existing Compose routing
The system SHALL provide a dedicated Model Library Compose screen reachable primarily from the existing Chat model status control and compatible with the existing `AppScreen` enum-based routing. Setup MAY direct the user to the library when no usable model exists. The screen SHALL expose model state, active selection, import, download, load/switch, eject, delete, progress, and actionable errors.

#### Scenario: User opens Model Library from Chat
- **WHEN** the user activates the model status control in Chat
- **THEN** Loki opens the Model Library screen without introducing a navigation framework
- **AND** the user can inspect installed and available catalog models

#### Scenario: No usable model exists
- **WHEN** Loki has no valid active model after initialization
- **THEN** Setup or the initial app state offers a path to Model Library
- **AND** Chat is not presented as if a usable model were active


