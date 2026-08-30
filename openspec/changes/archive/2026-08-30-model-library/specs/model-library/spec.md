## ADDED Requirements

### Requirement: The system maintains a persistent model registry
The system SHALL represent every installed model as a first-class `ModelRecord` in a persistent JSON manifest. A record MUST include a stable ID, display metadata, runtime, format, managed artifact location, source/origin, size, lifecycle information, and optional integrity metadata. Model selection MUST use model identity rather than file existence or filename extension.

#### Scenario: Multiple runtimes coexist
- **WHEN** the user imports one LiteRT-LM artifact and one GGUF artifact
- **THEN** both appear as separate records with their respective runtime and format
- **AND** neither record replaces the other merely because both are installed

#### Scenario: Registry survives process restart
- **WHEN** the application restarts after a model has been registered
- **THEN** the model record and active model ID are restored from managed persistence
- **AND** a missing or invalid artifact is reported as unavailable rather than selected as usable

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

### Requirement: Model identification and validation are evidence-based
The system SHALL separate format/runtime detection from metadata collection and validation. It MUST NOT infer Gemma or another family solely from a generic `.bin` extension. When reliable automatic identification is unavailable, the system SHALL allow the user to provide or confirm display name, family, runtime, and format. A failed runtime-aware validation MUST prevent registration as usable.

#### Scenario: Unknown local artifact requires confirmation
- **WHEN** a selected local file does not provide enough reliable metadata for identification
- **THEN** Loki asks the user to confirm or provide the missing identity and runtime metadata
- **AND** Loki does not invent a model family

#### Scenario: Invalid artifact is rejected
- **WHEN** validation cannot establish that an artifact is usable by its declared runtime and format
- **THEN** the import/download operation fails with an actionable error
- **AND** the artifact is not registered as a usable model

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

### Requirement: Downloads are integrity-checked and atomically finalized
The system SHALL download model artifacts as streams into temporary files, report progress when the source provides a length, verify expected size and SHA-256 when available, validate the artifact before installation, and atomically finalize the managed artifact. A failed verification SHALL not produce an installed usable record.

#### Scenario: Verified download is installed
- **WHEN** a catalog artifact downloads successfully and all available integrity and runtime validation checks pass
- **THEN** Loki atomically finalizes the artifact in managed storage
- **AND** registers it as `DOWNLOADED` without claiming it is loaded

#### Scenario: Checksum mismatch is rejected
- **WHEN** a downloaded artifact's SHA-256 differs from the catalog checksum
- **THEN** Loki rejects the artifact
- **AND** removes the finalized or temporary invalid file
- **AND** leaves no usable registry entry for that download

### Requirement: Model lifecycle distinguishes loading, ejecting, and deleting
The system SHALL support `DOWNLOADED`, `LOADED`, and unavailable/not-downloaded states, with at most one loaded model initially. Eject SHALL unload runtime resources while retaining the model record and artifact. Delete SHALL be an explicit operation that removes the artifact and registry entry, and SHALL unload a loaded model before removal.

#### Scenario: Model is ejected
- **WHEN** the user ejects the currently loaded model
- **THEN** Loki releases the model runtime resources
- **AND** the model remains registered with its artifact on disk in a downloaded/unloaded state

#### Scenario: Model is switched
- **WHEN** the user loads a different downloaded model
- **THEN** Loki unloads the current model before loading the selected model
- **AND** updates the active model only after the new model loads successfully
- **AND** does not leave two models intentionally loaded

#### Scenario: Loaded model is deleted explicitly
- **WHEN** the user confirms deletion of the loaded model
- **THEN** Loki unloads it, removes its artifact and registry entry, and clears or updates the active model
- **AND** deletion is never triggered by ejecting or switching alone

### Requirement: Existing accessible legacy models are adopted safely
The system SHALL perform an idempotent migration check for accessible application-owned legacy model locations, including the current app's internal/external `model.bin` and `model.gguf` conventions. It MUST NOT assume access to another package's private directory. Shared-storage artifacts SHALL be imported through SAF.

#### Scenario: Legacy model is accessible and valid
- **WHEN** migration finds a valid legacy artifact in an accessible Loki-owned location
- **THEN** Loki adopts or copies it into managed model storage
- **AND** creates a model record without requiring the user to re-download it

#### Scenario: Legacy model is inaccessible
- **WHEN** the only known copy is in another application's private sandbox
- **THEN** migration does not attempt to access that path
- **AND** the user is directed to SAF import or another supported acquisition path

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
