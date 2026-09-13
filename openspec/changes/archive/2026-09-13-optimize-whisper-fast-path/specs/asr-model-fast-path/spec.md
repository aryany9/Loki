## ADDED Requirements

### Requirement: Accelerated CPU inference for the STT engine
The default `SttEngine` implementation (`LiteRtWhisperEngine`) SHALL define its acceleration setting as a single, clearly named configuration point. XNNPACK MUST be disabled for the Whisper artifact because the artifact is a multi-subgraph model with `odml.*` stablehlo composite ops, which cause an uncatchable native SIGSEGV during interpreter construction when XNNPACK dispatch is enabled (reproduced on device 2026-09-13). The setting MUST NOT be silently reintroduced; revisiting it requires a TFLite/XNNPACK runtime version that skips `odml.*` composite ops. Acceleration in this change is delivered by the int8 quantized artifact, which is safe for this artifact and runs faster on generic CPU kernels.

#### Scenario: Interpreter constructs without XNNPACK
- **WHEN** the engine initializes the TFLite interpreter for the Whisper artifact
- **THEN** the interpreter options disable XNNPACK via the single named configuration point
- **AND** interpreter construction completes without a native crash and both `encode`/`decode` signatures resolve

#### Scenario: Composite-subgraph artifact is protected from XNNPACK dispatch
- **WHEN** a future contributor considers re-enabling XNNPACK for this artifact
- **THEN** the configuration point documents the SIGSEGV root cause and the runtime requirement for revisiting it

### Requirement: Quantized artifact preferred for STT
The STT engine SHALL, when loading an ASR model record that contains multiple artifact variants, prefer a quantized variant that exists on disk, falling back to a full-precision variant, and failing only when no resolvable `.tflite` artifact exists.

#### Scenario: Both variants present
- **WHEN** an ASR model record resolves with both a quantized and a full-precision artifact installed
- **THEN** the engine loads the quantized artifact

#### Scenario: Quantized artifact missing
- **WHEN** only the full-precision artifact is present on disk
- **THEN** the engine loads the full-precision artifact and voice transcription continues to work

#### Scenario: No artifact present
- **WHEN** no `.tflite` artifact for the ASR model exists on disk
- **THEN** `load` returns false and the ASR runtime reports not-ready, without crashing

### Requirement: Variant selection is catalog-driven
The engine MUST NOT hardcode artifact filenames for ASR selection. Artifact preference SHALL derive from the model record's artifact metadata so catalog changes take effect without engine code changes.

#### Scenario: Catalog changes preferred artifact
- **WHEN** the catalog marks a different artifact variant as preferred
- **THEN** the engine resolves and loads that artifact on next load without code modification
