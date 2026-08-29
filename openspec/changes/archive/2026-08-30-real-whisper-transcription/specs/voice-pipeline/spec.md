## ADDED Requirements

### Requirement: Real Whisper transcription output
The STT engine SHALL transcribe recorded PCM audio by executing the loaded LiteRT Whisper model, and the emitted final transcript SHALL reflect the actual recognized speech.

#### Scenario: Transcript varies with speech
- **WHEN** two different utterances are spoken and recorded by the pipeline
- **THEN** the engine emits final transcripts corresponding to the spoken content, which are not identical for different utterances

#### Scenario: Placeholder output is never emitted
- **WHEN** any utterance is transcribed
- **THEN** the emitted transcript is never a fixed constant string (e.g. `"Voice command received"`)

### Requirement: STT engine initialization is real and readiness is accurate
The STT engine SHALL construct its inference interpreter during initialization, SHALL report `isInitialized` as true only after successful initialization, and the model runtime readiness signal for the ASR runtime SHALL reflect this state.

#### Scenario: Voice invocation before STT engine is ready
- **WHEN** the assistant is invoked before the Whisper engine has finished initializing
- **THEN** the turn does not enter the Listening state and instead surfaces an Error state with a meaningful message

#### Scenario: Successful initialization
- **WHEN** a valid whisper `.tflite` artifact is loaded and the interpreter is constructed
- **THEN** the engine reports initialized and `isRuntimeReady(LITERT_ASR)` returns true

#### Scenario: Initialization failure surfaces
- **WHEN** the interpreter cannot be constructed from the artifact
- **THEN** initialization fails and subsequent voice turns surface an Error state rather than producing fabricated transcripts
