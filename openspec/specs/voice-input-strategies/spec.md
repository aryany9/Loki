# voice-input-strategies Specification

## Purpose
Enables dynamic per-turn selection between direct-audio processing (for multimodal audio-capable LLMs) and offline STT transcription (for text-only LLMs), including recording duration limits, failure demotion, and seamless model switching.

## Requirements

### Requirement: Model capability metadata drives voice-input strategy
The system SHALL select the voice-input strategy per turn based on the active LLM model's capability metadata stored on the model record, never on engine-level hardcoding. A record with confirmed audio-input capability SHALL use the direct-audio strategy; a record without it SHALL use the STT-transcribe strategy.

#### Scenario: Audio-capable active model
- **WHEN** the active LLM model record declares audio-input capability (VERIFIED or USER_CONFIRMED) and the user invokes the voice assistant
- **THEN** the turn uses the direct-audio strategy without requiring the STT engine

#### Scenario: Text-only active model
- **WHEN** the active LLM model record has no audio-input capability and the user invokes the voice assistant
- **THEN** the turn uses the STT-transcribe strategy, which requires the STT engine to be ready

### Requirement: Direct-audio strategy sends recorded audio to the LLM
The direct-audio strategy SHALL record microphone audio (16 kHz PCM 16-bit, maximum 30 seconds), package it as a WAV byte array, and send it to the LLM as a `Content.AudioBytes` turn together with the user's text prompt. The audio turn SHALL participate in the same ReAct JSON tool-calling loop as text turns.

#### Scenario: Voice command routed as audio
- **WHEN** a user speaks a command with an audio-capable model active
- **THEN** the recorded audio is delivered to the LLM as audio content within the tool-calling loop and the assistant produces a spoken response

#### Scenario: Recording duration is capped
- **WHEN** a user speaks longer than 30 seconds with an audio-capable model active
- **THEN** recording stops at 30 seconds and the captured audio is processed

### Requirement: STT-transcribe strategy converts speech to text before the LLM
The STT-transcribe strategy SHALL transcribe the recorded utterance using the STT engine and send the resulting text to the LLM as a text turn. This strategy SHALL remain a permanent path for text-only models.

#### Scenario: Voice command with a text-only model
- **WHEN** a user speaks a command with a text-only model active
- **THEN** the utterance is transcribed by the STT engine and the transcript is sent to the LLM as text

### Requirement: Failed direct-audio turns demote to STT fallback
If a direct-audio turn fails at the model boundary (e.g. the model rejects audio input despite its capability flag), the system SHALL retry the same turn once through the STT-transcribe strategy when the STT engine is available, and SHALL surface the demotion in turn logs and assistant state. If the STT engine is unavailable, the turn SHALL surface an error state.

#### Scenario: Model rejects audio despite capability flag
- **WHEN** a direct-audio turn fails at the model boundary and the STT engine is loaded
- **THEN** the system retries the turn via STT transcription and completes it if possible
- **AND** the demotion is logged

#### Scenario: Demotion impossible
- **WHEN** a direct-audio turn fails at the model boundary and no STT model is loaded
- **THEN** the assistant surfaces an error state for the turn

### Requirement: Strategy selection follows model switching
The system SHALL re-evaluate the voice-input strategy from the active model record on each turn. Switching between models SHALL NOT eject unrelated runtime models, and the voice feature SHALL work after a switch without requiring the user to reload the assistant.

#### Scenario: Switch from audio-capable to text-only model
- **WHEN** the user switches the active LLM from an audio-capable model to a text-only model and then invokes the voice assistant
- **THEN** the turn uses the STT-transcribe strategy

#### Scenario: Switch from text-only to audio-capable model
- **WHEN** the user switches the active LLM from a text-only model to an audio-capable model and then invokes the voice assistant
- **THEN** the turn uses the direct-audio strategy without requiring the STT engine to be ready
