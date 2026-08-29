## 1. Model capability metadata (core/models)

- [ ] 1.1 Add `ModelRecordCapabilities` (confidence-tagged `audioInput` via `ModelMetadataField`) to `ModelTypes.kt` and attach it to `ModelRecord` with a safe default (no audio)
- [ ] 1.2 Update `downloadCatalogEntry()` registration to carry catalog capability tags into the record as VERIFIED; map catalog tags (e.g. `audio-input`) to the new field; add `gemma-4-E4B-it` audio capability to the bundled catalog
- [ ] 1.3 Add the audio-support toggle (default false) to the model import flow and register imported records with USER_CONFIRMED confidence when enabled
- [ ] 1.4 Unit tests: registration preserves capabilities; default/absent capability decodes as text-only; manifest round-trip

## 2. LiteRT-LM engine audio support (core/llm)

- [ ] 2.1 Add `audioBackend = Backend.CPU()` to `EngineConfig` construction in `tryInitEngine()`
- [ ] 2.2 Extend the `LlmEngine` send path to accept a WAV audio byte array and build `Contents.of(Content.AudioBytes(wav), Content.Text(prompt))` for `sendMessageAsync`
- [ ] 2.3 Make `LiteRtLlmEngine` capability reporting read from the loaded `ModelRecord` capabilities instead of hardcoded values
- [ ] 2.4 Implement WAV packaging of 16 kHz PCM 16-bit recordings (44-byte header, Gallery's `genByteArrayForWav` pattern) and a 30 s recording cap
- [ ] 2.5 Unit tests: WAV header correctness; content ordering (audio before text); capability sourced from record

## 3. Voice-input strategy orchestration (core/assistant)

- [ ] 3.1 Introduce `VoiceInputStrategy` (direct-audio / stt-transcribe) selected per turn from the active LLM record's capability metadata
- [ ] 3.2 Make the `AssistantSession.startTurn()` precheck strategy-aware: require LITERT_ASR readiness only for the STT strategy
- [ ] 3.3 Implement demotion: on direct-audio model-boundary failure, retry the same turn once via STT (when available); log and surface demotion in `AssistantState`; error state when STT unavailable
- [ ] 3.4 Turn logging records strategy, recording duration, and demotion events (transcript text only exists on the STT path)
- [ ] 3.5 Unit tests: strategy selection per capability; precheck gating; demotion retry and error paths

## 4. UI and setup (core/ui, app)

- [ ] 4.1 Make setup-completion criteria capability-aware (STT model no longer mandatory when the active LLM is audio-capable)
- [ ] 4.2 Surface active strategy / capability info in the model library and assistant UI where relevant

## 5. Device validation

- [ ] 5.1 Gemma 4 E4B IT: direct-audio turn produces a spoken response and routes tool calls through the ReAct loop
- [ ] 5.2 Text-only model: STT-transcribe turn works end to end (with the `real-whisper-transcription` change landed)
- [ ] 5.3 Switch models mid-session: strategy flips correctly in both directions without ejecting the ASR model
- [ ] 5.4 Force a bad audio flag (USER_CONFIRMED on a text-only model): verify one STT demotion retry completes the turn and the demotion is logged
- [ ] 5.5 30 s cap: speak > 30 s and verify recording stops and processing proceeds
