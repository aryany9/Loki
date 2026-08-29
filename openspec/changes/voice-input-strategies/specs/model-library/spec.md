## ADDED Requirements

### Requirement: Model records carry capability metadata
The system SHALL store per-model capability metadata on the `ModelRecord` (including audio-input support) with a confidence level. Capability values SHALL be populated from bundled catalog entries as VERIFIED, and from an explicit user toggle at import time (defaulting to false) as USER_CONFIRMED. Catalog capability tags SHALL NOT be dropped during registration.

#### Scenario: Catalog entry with audio capability is downloaded
- **WHEN** a bundled catalog entry declares audio-input capability and the user downloads it
- **THEN** the registered model record carries audio-input capability with VERIFIED confidence

#### Scenario: Imported model with user-declared audio support
- **WHEN** the user imports a model and enables the audio-support toggle
- **THEN** the registered model record carries audio-input capability with USER_CONFIRMED confidence
- **AND** the toggle defaults to false when untouched

#### Scenario: Record without capability signal is treated as text-only
- **WHEN** a model record has no confirmed audio-input capability
- **THEN** the voice pipeline treats the model as text-only for strategy selection
