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


