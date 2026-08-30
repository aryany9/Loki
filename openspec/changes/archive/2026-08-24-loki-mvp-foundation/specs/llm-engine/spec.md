## ADDED Requirements

### Requirement: `LlmEngine` interface abstracts all LLM inference
The system SHALL define an `LlmEngine` interface that decouples `ConversationManager` and all layers above from any specific LLM backend or model format. No llama.cpp-specific types, GGUF concepts, or backend-specific APIs SHALL appear above `LlmEngine`.

#### Scenario: Backend is swappable without upstream changes
- **WHEN** the active `LlmEngine` implementation is replaced (e.g., `LlamaCppLlmEngine` → `LiteRtLlmEngine`)
- **THEN** `ConversationManager`, `ToolRegistry`, and all other modules function correctly without code changes

---

### Requirement: `LlmEngine` supports streaming token generation
The `LlmEngine` interface SHALL support streaming generation so that partial tokens can be delivered as they are produced, enabling future streaming TTS integration.

#### Scenario: Streaming response emitted
- **WHEN** the `LlmEngine` is asked to generate a response with streaming enabled
- **THEN** it emits tokens incrementally as they are produced
- **AND** a completion event is emitted when generation finishes

---

### Requirement: `LlmEngine` supports grammar-constrained output
The `LlmEngine` interface SHALL accept an optional grammar parameter (e.g., GBNF string). When provided, the engine SHALL constrain token sampling to outputs matching the grammar.

#### Scenario: Grammar-constrained tool-call generation
- **WHEN** `LlmEngine.generate()` is called with a GBNF grammar defining valid tool-call structure
- **THEN** the output is syntactically valid JSON conforming to the grammar
- **AND** no syntactically invalid tool calls are produced across a benchmark of 20+ diverse prompts (Spike 2 validation)

#### Scenario: Grammar not supported by backend
- **WHEN** the active backend does not support grammar constraints
- **THEN** the engine falls back to prompt-only JSON mode
- **AND** logs a warning indicating grammar constraints are unavailable

---

### Requirement: `ModelManager` handles model lifecycle
The system SHALL provide a `ModelManager` that manages model downloading, storage, loading into memory, and unloading. The `LlmEngine` SHALL not manage model files directly.

#### Scenario: Model downloaded on first launch
- **WHEN** Loki is launched for the first time with no model present
- **THEN** `ModelManager` prompts the user to download the default model
- **AND** downloads and stores the model locally

#### Scenario: Model loaded before inference
- **WHEN** a voice session begins
- **THEN** `ModelManager` ensures the active model is loaded into memory before the first inference request

#### Scenario: Model unloaded on session end
- **WHEN** the voice session ends and no inference is pending
- **THEN** `ModelManager` may unload the model to reclaim memory (configurable)

---

### Requirement: LLM inference is cancellable
An in-progress `LlmEngine` generation SHALL be cancellable and SHALL stop producing tokens promptly when cancelled.

#### Scenario: Generation cancelled mid-stream
- **WHEN** `LlmEngine.generate()` is cancelled (e.g., user interrupts session)
- **THEN** token emission stops within a reasonable time
- **AND** the engine is in a ready state for the next inference request

---

### Requirement: `LlamaCppLlmEngine` is the first production backend
The system SHALL provide `LlamaCppLlmEngine` as the first concrete `LlmEngine` implementation, targeting ARM64 Android via JNI, supporting GGUF quantized models, Vulkan GPU acceleration where available, and native GBNF grammar-constrained generation.

#### Scenario: Inference runs offline on ARM64
- **WHEN** `LlamaCppLlmEngine` is initialized with a GGUF model and the device is in airplane mode
- **THEN** inference completes successfully without any network access
