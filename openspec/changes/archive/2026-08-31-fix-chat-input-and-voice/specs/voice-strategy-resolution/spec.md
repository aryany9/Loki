## ADDED Requirements

### Requirement: Whisper STT provisioning for text-only models
A small whisper TFLite ASR model SHALL be available as a bundled-catalog entry (`ModelRuntime.LITERT_ASR`) downloadable through the model library with the same integrity/atomicity guarantees as LLM models. When the active LLM is text-only, the chat mic path SHALL resolve STT_TRANSCRIBE once the ASR model is LOADED; if the ASR model is not downloaded, the chat UI SHALL present an actionable error with a one-tap download action (reusing the existing download pipeline). Android `SpeechRecognizer` SHALL NOT be used for chat voice input.

#### Scenario: First mic tap with undownloaded ASR model
- **WHEN** the user taps the mic while a text-only model is active and the ASR model is not downloaded
- **THEN** an actionable banner offers the voice-model download; after download completes, subsequent mic taps record and transcribe via whisper

#### Scenario: Text-only model voice turn works offline
- **WHEN** the ASR model is LOADED and the device is offline
- **THEN** a voice turn records, transcribes on-device, and produces a response with no network access

### Requirement: Shared voice strategy resolution
The system SHALL expose a single voice-input strategy resolver that determines, from the active LLM model record's audio-input capability (requiring VERIFIED or USER_CONFIRMED confidence), whether a voice turn uses DIRECT_AUDIO (raw audio to the multimodal LLM), STT_TRANSCRIBE (offline whisper transcription, requiring STT runtime readiness), or is UNAVAILABLE (with a reason). Both the chat mic path and the assistant voice path SHALL use this resolver; no caller SHALL hardcode a strategy.

#### Scenario: Audio-capable model resolves DIRECT_AUDIO
- **WHEN** the active model record reports audio-input supported with VERIFIED/USER_CONFIRMED confidence
- **THEN** the resolver returns DIRECT_AUDIO for both chat and assistant voice turns, with no whisper model required

#### Scenario: Text-only model falls back to STT
- **WHEN** the active model does not support audio input
- **THEN** the resolver returns STT_TRANSCRIBE when the STT runtime is ready, and UNAVAILABLE(STT_NOT_READY) otherwise

### Requirement: Chat mic uses the resolved strategy
The chat composer mic button SHALL execute voice input according to the resolver result: DIRECT_AUDIO records audio via the shared recorder and sends it as `audioBytes` through the persistent chat session (reusing streaming, tools, and persistence); STT_TRANSCRIBE transcribes via whisper and sends text; UNAVAILABLE SHALL present a visible, dismissible error message in the chat UI indicating the reason and the remediation path (Model Library).

#### Scenario: Voice input on audio-capable model
- **WHEN** the user taps the mic with an audio-capable model active and speaks
- **THEN** the recording is sent as audio to the LLM through the chat session, the response streams into the chat, and the turn is persisted with a `[Voice Audio]` user placeholder

#### Scenario: Unavailable voice input is visible
- **WHEN** the resolver returns UNAVAILABLE and the user taps the mic
- **THEN** a visible error message appears in the chat UI explaining the reason; the recording flag does not silently reset with no feedback
