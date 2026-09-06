## ADDED Requirements

### Requirement: Resolver accepts audio or transcript input
The `ConfirmationResolver` SHALL accept either `audioBytes: ByteArray?` (WAV-encoded PCM for DirectAudio devices) or `transcript: String?` (pre-transcribed text for STT-Transcribe devices) as the user's response input. Exactly one of the two SHALL be non-null per call.

#### Scenario: DirectAudio device invokes resolver with audio
- **WHEN** the active voice input strategy is `DIRECT_AUDIO` and `taskState.expectedSemantics == CONFIRMATION`
- **THEN** `ConfirmationResolver.resolve()` is called with the captured WAV bytes and `transcript = null`
- **AND** the audio bytes are passed directly to `LiteRtLlmEngine.generate()` with the classification prompt and grammar

#### Scenario: STT-Transcribe device invokes resolver with transcript
- **WHEN** the active voice input strategy is `STT_TRANSCRIBE` and `taskState.expectedSemantics == CONFIRMATION`
- **THEN** `ConfirmationResolver.resolve()` is called with the Whisper transcript and `audioBytes = null`
- **AND** the transcript text is embedded in the classification prompt passed to `LiteRtLlmEngine.generate()`

---

### Requirement: Resolver uses a stripped, semantically-anchored classification prompt
The `ConfirmationResolver` SHALL construct a prompt containing only: (1) the pending confirmation question text, (2) definitions of `CONFIRMED`, `DECLINED`, and `UNKNOWN` with multilingual vocabulary examples, and (3) the output instruction. The prompt SHALL NOT include any conversation history, tool schemas, system foundation instructions, or task-state prose.

#### Scenario: Prompt excludes conversation context
- **WHEN** `ConfirmationResolver.resolve()` is invoked
- **THEN** the prompt sent to `LiteRtLlmEngine` contains no prior conversation turns and no tool call history

#### Scenario: Multilingual affirmative is correctly classified
- **WHEN** the user responds with "Haan, karo" or "sure" or "go ahead"
- **THEN** the resolver returns `ConfirmationOutcome.CONFIRMED`

#### Scenario: Multilingual negative is correctly classified
- **WHEN** the user responds with "nahi" or "mat karo" or "cancel" or "no"
- **THEN** the resolver returns `ConfirmationOutcome.DECLINED`

#### Scenario: Ambiguous response maps to UNKNOWN
- **WHEN** the user responds with an unclear utterance that is neither a clear affirmative nor negative
- **THEN** the resolver returns `ConfirmationOutcome.UNKNOWN`

---

### Requirement: Resolver output is grammar-constrained to three semantic tokens
The `ConfirmationResolver` SHALL pass a GBNF grammar constraining model output to exactly one of `"CONFIRMED"`, `"DECLINED"`, or `"UNKNOWN"` in the `LiteRtLlmEngine.generate()` call. The model SHALL NOT be able to produce free-form text, questions, or any other output from this call.

#### Scenario: Grammar prevents question output
- **WHEN** the audio encoder is uncertain and the model would otherwise regenerate the confirmation question
- **THEN** the grammar constraint forces output to one of the three permitted tokens
- **AND** `ConfirmationOutcome.UNKNOWN` is returned, triggering a re-prompt rather than loop exit

#### Scenario: Unexpected engine output maps to UNKNOWN
- **WHEN** the inference engine returns a string not matching any of the three expected tokens
- **THEN** `ConfirmationOutcome.from(result)` returns `UNKNOWN` as a safe default
- **AND** the follow-up loop re-prompts the user

---

### Requirement: Resolver is stateless with no persistent KV context
The `ConfirmationResolver` SHALL be a stateless inference operation. It SHALL NOT maintain a KV cache, conversation history, or session lifecycle across calls. Each call to `resolve()` is an independent `generate()` invocation on the existing `LiteRtLlmEngine` instance.

#### Scenario: Sequential confirmation calls are independent
- **WHEN** `resolve()` is called twice in sequence (e.g., first returns UNKNOWN, second returns CONFIRMED)
- **THEN** the second call has no memory of the first call's audio or prompt
- **AND** each call uses a fresh generate context
