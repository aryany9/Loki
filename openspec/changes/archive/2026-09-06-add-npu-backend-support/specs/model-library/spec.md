# Spec: Model Library — NPU Model Compatibility (Advisory)

## ADDED Requirements

### Requirement: SoC-targeted NPU models import with advisory compatibility state

Model import SHALL succeed for NPU-targeted `.litertlm` artifacts on any device. The model record SHALL carry NPU target metadata (SoC / HTP generation parsed from the `qualcomm_<soc>` artifact naming convention). When the current device does not match the model's NPU target, the record SHALL be marked **unavailable for execution** with a clear reason; it SHALL NOT be rejected at import and SHALL become executable on a matching device. Only structural failures (corrupt file, unsupported format) reject import, unchanged from existing behavior.

#### Scenario: NPU model imported on a matching device
- **GIVEN** `gemma-4-E2B-it_qualcomm_sm8750.litertlm` imported on an SM8750 device
- **WHEN** the import completes
- **THEN** the record is marked NPU-compatible and available for execution

#### Scenario: NPU model imported on a non-matching device
- **GIVEN** the same model imported on a device whose SoC does not match the model's target
- **WHEN** the import completes
- **THEN** the record is created successfully and flagged unavailable-for-execution with a reason (e.g. "targets SM8750 (HTP v79); this device is <SoC>")
- **AND** the model library UI shows the advisory state
- **AND** the model is never selected for NPU execution on this device

#### Scenario: Generic models are unaffected
- **GIVEN** a generic (non-SoC-targeted) `.litertlm` model
- **WHEN** imported on any device
- **THEN** the record has no NPU target metadata and behaves exactly as before this change

### Requirement: NPU-targeted models are validated structurally at import

Import validation for NPU-targeted `.litertlm` artifacts SHALL be structural only — container magic (`LITERTLM`), readable metadata table, and file readability — performed via `LitertLmContainerInspector` with NPU metadata extraction (`npuTargetSoc`, `isNpuTargeted`) detected from container header/metadata content, with the `qualcomm_<soc>` filename convention as fallback. No live `Engine.initialize()` SHALL be attempted for NPU-targeted models during import, on any device. Generic (non-NPU-targeted) models retain the existing live-init validation.

#### Scenario: NPU model validates without live engine init
- **GIVEN** `gemma-4-E2B-it_qualcomm_sm8750.litertlm` on any device
- **WHEN** the import validator runs
- **THEN** validation inspects the container structurally and returns Valid
- **AND** no `Engine.initialize()` is invoked during import

#### Scenario: NPU target detection survives renaming (where possible)
- **GIVEN** an NPU-targeted container whose header/metadata declares its backend target
- **WHEN** the file is renamed before import
- **THEN** the inspector still reports `isNpuTargeted`/`npuTargetSoc` from container content
- **AND** the filename convention is used only as a fallback signal

