# Spec: Voice Assistant Workflow & LiteRT Whisper Integration

## ADDED Requirements

### Requirement: LiteRT-based Whisper STT Engine

The system MUST provide an `SttEngine` implementation (`LiteRtWhisperEngine`) that transcribes 16kHz PCM audio from VAD using a LiteRT-based Whisper `.tflite` model.

#### Scenario: Transcribing spoken utterance
- **GIVEN** `LiteRtWhisperEngine` initialized with a valid Whisper `.tflite` model
- **WHEN** the user speaks "Set a timer for 5 minutes" and VAD detects silence
- **THEN** `LiteRtWhisperEngine` pre-processes the audio and emits `SttEvent.FinalResult("Set a timer for 5 minutes")`.

### Requirement: Single-Turn Voice Assistant Session Policy

The Voice Assistant MUST execute turns in single-turn voice sessions (`maxTurns = 1`) without automatically injecting long-term ChatScreen conversation history.

#### Scenario: Invoking Voice Assistant command
- **GIVEN** an active voice invocation from the session overlay
- **WHEN** the user utters a command
- **THEN** the request is processed in a single-turn `ConversationSession` focused on intent execution and low latency.

### Requirement: Multi-Stage Session Interruption & Cancellation

The Voice Assistant MUST propagate cancellation across all active subsystems (STT recording, LLM token generation, tool execution, and TTS audio playback) when the user cancels or dismisses the session.

#### Scenario: User cancels session during active LLM generation
- **GIVEN** `LiteRtLlmEngine` actively generating response tokens during a voice turn
- **WHEN** the user taps "Dismiss" on the session overlay
- **THEN** native LLM generation is cancelled via `cancelProcess()`, TTS playback is stopped, and session state returns to `Idle`.

### Requirement: Dual TTS Provider Selection

The Voice Assistant MUST synthesize spoken responses using the selected TTS provider (`AndroidTtsEngine` or `CustomModelTtsEngine`).

#### Scenario: Speaking response using selected TTS provider
- **GIVEN** a completed voice turn with response text "The time is 9:00 AM" and `AndroidTtsEngine` selected
- **WHEN** the voice session transitions to speaking
- **THEN** `AndroidTtsEngine.speak()` synthesizes spoken audio output on the device.
