# Spec: LLM Engine — NPU Backend & Observable Fallback

## MODIFIED Requirements

### Requirement: Backend Selection & Fallback

The engine MUST support `ExecutionBackend` selection (`AUTOMATIC`, `NPU`, `GPU`, `CPU`). `AUTOMATIC` (the default) MUST resolve an ordered candidate chain **NPU → GPU → CPU**, where NPU is included only when the hardware probe reports NPU usable AND the model is NPU-compatible. Backend initialization attempts MUST be transactional (native resources fully released between attempts) and observable (each attempt recorded with backend, duration, outcome, and failure reason; the resolved backend and failed-attempt reasons surfaced via engine state).

#### Scenario: AUTOMATIC resolves NPU on a compatible device and model
- **GIVEN** a device whose probe reports `npuUsable=true` and a model record with matching NPU target metadata
- **WHEN** `initializeAsync()` is called with `ExecutionBackend.AUTOMATIC`
- **THEN** the engine attempts NPU first (constructing `Backend.NPU(nativeLibraryDir)`) and, on success, reports NPU as the active backend

#### Scenario: Transactional fallback with observable report
- **GIVEN** `ExecutionBackend.AUTOMATIC` where the NPU attempt fails at initialization
- **WHEN** the engine proceeds to the next candidate
- **THEN** all native resources from the failed attempt are released before the GPU attempt starts
- **AND** the init report records the NPU attempt (backend, durationMs, failure reason) and the GPU fallback
- **AND** the UI can display the active backend and why earlier candidates failed

#### Scenario: Explicit NPU selection is exclusive
- **GIVEN** the user explicitly selects the NPU backend via the advanced setting
- **WHEN** NPU initialization fails
- **THEN** the load fails with the NPU error surfaced to the user
- **AND** the engine does NOT silently substitute another backend

#### Scenario: NPU never attempted when not usable or not compatible
- **GIVEN** a device with `npuUsable=false` OR a model without NPU target metadata
- **WHEN** `ExecutionBackend.AUTOMATIC` is used
- **THEN** the candidate chain contains only GPU and CPU
- **AND** no NPU initialization attempt is made

### Requirement: NPU sampler configuration exclusion

The engine MUST NOT customize `ConversationConfig.samplerConfig` when the active backend is NPU.

#### Scenario: Conversation creation under NPU
- **GIVEN** the engine initialized successfully on the NPU backend
- **WHEN** `startConversation()` is called with an `AgentConfig` containing generation settings
- **THEN** the conversation is created without sampler customization
- **AND** system instruction handling is unchanged

## ADDED Requirements

### Requirement: Hardware NPU capability probe

The engine SHALL run a hardware capability probe once per engine initialization that detects: the NPU vendor from device SoC properties (Qualcomm/MediaTek/Google Tensor/Samsung/Unknown), the HTP generation from a pinned SoC→generation mapping (`supported_soc.csv` data), and `npuUsable` — whether the QNN runtime libraries and the LiteRT vendor dispatch library are actually reachable from `applicationInfo.nativeLibraryDir`. Probe results SHALL be exposed as observable engine capabilities and SHALL NOT by themselves trigger backend attempts.

#### Scenario: Probe on a device without NPU libraries
- **GIVEN** a Qualcomm SoC device where QNN/dispatch libraries are absent from `nativeLibraryDir`
- **WHEN** the probe runs
- **THEN** `npuVendor` is Qualcomm and `npuUsable` is false
- **AND** no NPU engine initialization is triggered by the probe itself

#### Scenario: Probe detection is pure and unit-testable
- **GIVEN** synthetic SoC property inputs (manufacturer, model, hardware, board)
- **WHEN** vendor detection and generation mapping run
- **THEN** results are deterministic and testable without an Android device

### Requirement: QNN runtime is sourced only from the official Maven artifact

The build SHALL obtain QNN runtime libraries exclusively via the pinned `com.qualcomm.qti:qnn-runtime` Maven dependency. Qualcomm binaries SHALL NOT be committed to the repository, mirrored, or hosted by Loki. The vendor dispatch library SHALL be built from the LiteRT source revision matching the litertlm dependency and staged via Gradle. The APK SHALL retain the artifact's license/NOTICE files, and release verification SHALL confirm the pinned QNN version's license permits in-application object-code bundling.

#### Scenario: Build reproducibility without vendored binaries
- **GIVEN** a fresh checkout of the repository
- **WHEN** the project builds
- **THEN** QNN runtime libraries resolve from Maven at the pinned version
- **AND** no Qualcomm `.so` files exist in version control

#### Scenario: Packaging requirement for NPU
- **GIVEN** the release build configuration
- **WHEN** native libraries are packaged
- **THEN** `useLegacyPackaging` is enabled for jniLibs so the dispatch can locate vendor libraries via `nativeLibraryDir`
