## 1. Model capability metadata (core/models)

- [x] 1.1 Add `ModelRecordCapabilities` (confidence-tagged `audioInput` via `ModelMetadataField`) to `ModelTypes.kt` and attach it to `ModelRecord` with a safe default (no audio)
- [x] 1.2 Update `downloadCatalogEntry()` registration to carry catalog capability tags into the record as VERIFIED; map catalog tags (e.g. `audio-input`) to the new field; add `gemma-4-E4B-it` audio capability to the bundled catalog
- [x] 1.3 Add the audio-support toggle (default false) to the model import flow and register imported records with USER_CONFIRMED confidence when enabled
- [x] 1.4 Unit tests: registration preserves capabilities; default/absent capability decodes as text-only; manifest round-trip
- [x] 1.5 Integrate `LitertLmContainerInspector` into `ModelRegistry.reconcile()`, `LiteRtModelDetector`, and `MainActivity.finishImport()`, removing filename heuristics in favor of structural container inspection
- [x] 1.6 Unit tests for `LitertLmContainerInspector` (audio markers, vision markers, text-only, non-container bytes) and structural reconciliation in `ModelRegistryTest`

## 2. LiteRT-LM engine audio support (core/llm)

- [x] 2.1 Add `audioBackend = Backend.CPU()` to `EngineConfig` construction in `tryInitEngine()`
- [x] 2.2 Extend the `LlmEngine` send path to accept a WAV audio byte array and build `Contents.of(Content.AudioBytes(wav), Content.Text(prompt))` for `sendMessageAsync`
- [x] 2.3 Make `LiteRtLlmEngine` capability reporting read from the loaded `ModelRecord` capabilities instead of hardcoded values
- [x] 2.4 Implement WAV packaging of 16 kHz PCM 16-bit recordings (44-byte header, Gallery's `genByteArrayForWav` pattern) and a 30 s recording cap
- [x] 2.5 Unit tests: WAV header correctness; content ordering (audio before text); capability sourced from record

## 3. Voice-input strategy orchestration (core/assistant)

- [x] 3.1 Introduce `VoiceInputStrategy` (direct-audio / stt-transcribe) selected per turn from the active LLM record's capability metadata
- [x] 3.2 Make the `AssistantSession.startTurn()` precheck strategy-aware: require LITERT_ASR readiness only for the STT strategy
- [x] 3.3 Implement demotion: on direct-audio model-boundary failure, retry the same turn once via STT (when available); log and surface demotion in `AssistantState`; error state when STT unavailable
- [x] 3.4 Turn logging records strategy, recording duration, and demotion events (transcript text only exists on the STT path)
- [x] 3.5 Unit tests: strategy selection per capability; precheck gating; demotion retry and error paths

## 4. UI and setup (core/ui, app)

- [x] 4.1 Make setup-completion criteria capability-aware (STT model no longer mandatory when the active LLM is audio-capable)
- [x] 4.2 Surface active strategy / capability info in the model library and assistant UI where relevant

## 5. Device validation

- [x] 5.1 Gemma 4 E4B IT: direct-audio turn produces a spoken response and routes tool calls through the ReAct loop
- [x] 5.2 Text-only model: STT-transcribe turn works end to end (with the `real-whisper-transcription` change landed)
- [x] 5.3 Switch models mid-session: strategy flips correctly in both directions without ejecting the ASR model
- [x] 5.4 Force a bad audio flag (USER_CONFIRMED on a text-only model): verify one STT demotion retry completes the turn and the demotion is logged
- [x] 5.5 30 s cap: speak > 30 s and verify recording stops and processing proceeds
