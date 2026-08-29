### Requirement: Setup provisioning shows mandatory runtime state and acquisition path
`SetupScreen` SHALL display one card per mandatory runtime (`LITERT_LM`, `LITERT_ASR`). Each card SHALL show the runtime's name, a human-readable description, and its current lifecycle state: `NOT_DOWNLOADED`, `DOWNLOADED`, or `LOADED`. When a runtime is not `LOADED`, the card SHALL provide a CTA that navigates the user into `ModelLibraryScreen` pre-filtered to that runtime.

#### Scenario: Both runtimes not ready on fresh install
- **WHEN** the app is launched with no models installed
- **THEN** `SetupScreen` is shown with two cards: "LLM / Reasoning" and "ASR / Voice Recognition"
- **AND** both cards show a "❌ Required" state
- **AND** each card shows a "Choose / Download" CTA button

#### Scenario: LLM ready, ASR missing
- **WHEN** `LITERT_LM` is `LOADED` but `LITERT_ASR` is not
- **THEN** the LLM card shows "✅ Loaded" with the model's display name
- **AND** the ASR card shows "❌ Required" with a "Choose / Download" CTA
- **AND** the "Get Started" button is disabled

#### Scenario: Both runtimes ready
- **WHEN** both `LITERT_LM` and `LITERT_ASR` are `LOADED`
- **THEN** both cards show "✅ Loaded" with their respective model display names
- **AND** the "Get Started" button is enabled and navigates to `ChatScreen`

#### Scenario: ASR CTA navigates to Model Library
- **WHEN** the user taps "Choose / Download" on the ASR card in `SetupScreen`
- **THEN** `ModelLibraryScreen` is displayed
- **AND** the ASR runtime's downloadable catalog entry is visible in the catalog section
- **AND** any existing ASR model records are visible and loadable

### Requirement: Setup cards update reactively from manifest state
The `SetupScreen` runtime cards SHALL reflect the current `ModelManifest` state without requiring a screen refresh or navigation. When a model is loaded from `ModelLibraryScreen` and the user returns to `SetupScreen`, the card state SHALL already reflect the updated runtime state.

#### Scenario: User loads ASR model and returns to Setup
- **WHEN** the user navigates from `SetupScreen` → `ModelLibraryScreen` → loads ASR model → navigates back
- **THEN** the ASR card in `SetupScreen` shows "✅ Loaded"
- **AND** if LLM is also loaded, the "Get Started" button is enabled

### Requirement: Setup completion requires both mandatory runtimes LOADED
The "Get Started" / complete-setup action in `SetupScreen` SHALL only be available when `ModelLibraryManager.isRuntimeReady(LITERT_LM)` and `ModelLibraryManager.isRuntimeReady(LITERT_ASR)` are both `true`. It SHALL NOT be possible to complete Setup with one or both runtimes missing.

#### Scenario: Attempt to complete setup with missing ASR
- **WHEN** `LITERT_LM` is `LOADED` but `LITERT_ASR` is not
- **THEN** the "Get Started" button is disabled or absent
- **AND** the screen clearly indicates which runtime is still required

#### Scenario: Complete setup when both are ready
- **WHEN** both runtimes are `LOADED`
- **THEN** the "Get Started" button is enabled
- **AND** tapping it sets `isFirstRunComplete = true` and navigates to `ChatScreen`

### Requirement: ASR model is available in the bundled model catalog
The bundled `model_catalog.json` SHALL include a `ModelCatalogEntry` for `ModelRuntime.LITERT_ASR` representing `whisper_tiny_30s_f32.tflite`. The entry SHALL be surfaced in `ModelLibraryScreen`'s catalog section with a "Download" action.

#### Scenario: ASR catalog entry visible in Model Library
- **WHEN** `ModelLibraryScreen` is opened and no ASR model is installed
- **THEN** the catalog section shows a "Whisper Tiny (ASR)" entry with a "Download" button

#### Scenario: ASR catalog entry hidden after model is installed
- **WHEN** an ASR model record with the same ID is registered in the manifest
- **THEN** the catalog section does not show a duplicate download option for that entry

### Requirement: LiteRtWhisperEngine.load resolves artifact from centralized storage
`LiteRtWhisperEngine.load(ModelRecord)` SHALL resolve the `.tflite` artifact's absolute path via the injected `ModelStorage` instance using `storage.artifactFile(model.id, artifact.relativePath)` and SHALL call `initialize(path)` with that resolved path. It SHALL return `false` if no `.tflite` artifact is present in the record or if the resolved file does not exist on disk.

#### Scenario: Load with valid downloaded artifact
- **WHEN** `load(model)` is called and `storage.artifactFile(model.id, "whisper_tiny_30s_f32.tflite").exists()` is `true`
- **THEN** `initialize(path)` is called with the resolved absolute path
- **AND** `load()` returns `true`
- **AND** `isInitialized` is `true`

#### Scenario: Load with missing artifact file
- **WHEN** `load(model)` is called but the artifact file does not exist on disk
- **THEN** `load()` returns `false`
- **AND** `isInitialized` remains `false`

#### Scenario: Load with record containing no tflite artifact
- **WHEN** `load(model)` is called with a `ModelRecord` whose `artifacts` list contains no `.tflite` entry
- **THEN** `load()` returns `false`

### Requirement: First-run setup screen is shown on fresh install
On a fresh install, the app SHALL display `SetupScreen` before `ChatScreen`. Visibility of `SetupScreen` SHALL be driven by runtime readiness: `SetupScreen` is shown whenever `LITERT_LM` or `LITERT_ASR` is not `LOADED`, regardless of the `isFirstRunComplete` DataStore flag. The `isFirstRunComplete` flag is persisted as a UX state detail only and SHALL NOT be used as the sole navigation gate.

#### Scenario: Fresh install shows setup screen
- **WHEN** the app is launched for the first time (no models installed, `isFirstRunComplete = false`)
- **THEN** `SetupScreen` is shown instead of `ChatScreen`

#### Scenario: Models cleared after first run shows setup screen
- **WHEN** `isFirstRunComplete` is `true` but `LITERT_ASR` is not `LOADED` (e.g., model deleted)
- **THEN** `SetupScreen` is shown instead of `ChatScreen`
- **AND** the ASR card shows "❌ Required"

#### Scenario: Returning user with both models ready skips setup
- **WHEN** `isFirstRunComplete` is `true` AND both `LITERT_LM` and `LITERT_ASR` are `LOADED`
- **THEN** `ChatScreen` is shown directly without displaying `SetupScreen`

#### Scenario: Setup completes when both runtimes are ready
- **WHEN** the user taps "Get Started" with both runtimes `LOADED`
- **THEN** `isFirstRunComplete` is set to `true` in DataStore and the app navigates to `ChatScreen`
