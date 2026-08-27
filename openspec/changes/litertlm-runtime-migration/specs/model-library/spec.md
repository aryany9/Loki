## MODIFIED Requirements

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
