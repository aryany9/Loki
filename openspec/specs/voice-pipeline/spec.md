## Purpose
Voice pipeline: STT capture, TTS output, and session audio flow.
## Requirements
### Requirement: Local microphone capture during session
The voice pipeline SHALL capture audio from the device microphone exclusively during an active `VoiceInteractionSession`. No background audio recording SHALL occur outside of an active session.

#### Scenario: Microphone opens on session start
- **WHEN** a `VoiceInteractionSession` becomes active
- **THEN** the pipeline opens the microphone and begins capturing audio
- **AND** audio is processed locally without transmission to any remote server

#### Scenario: Microphone closes on session end
- **WHEN** the session is hidden or cancelled
- **THEN** microphone recording stops immediately
- **AND** the audio resource is released

---

### Requirement: Voice Activity Detection (VAD)
The voice pipeline SHALL include VAD to detect end-of-utterance so that STT is triggered on complete speech segments rather than continuously.

#### Scenario: End of speech detected
- **WHEN** the user stops speaking and silence exceeds the VAD silence threshold (300–500ms configurable)
- **THEN** the captured audio segment is passed to the STT engine
- **AND** no additional audio is captured for that utterance

---

### Requirement: `SttEngine` abstraction
The system SHALL define an `SttEngine` interface that decouples the voice pipeline from any specific STT implementation. All STT functionality SHALL be accessed exclusively through this interface.

#### Scenario: STT backend is replaceable
- **WHEN** a new `SttEngine` implementation is registered
- **THEN** the voice pipeline uses the new backend without changes to `ConversationManager` or any layer above `SttEngine`

---

### Requirement: Local STT as the default
The default `SttEngine` implementation (`WhisperSttEngine`) SHALL perform all speech recognition on-device without any network requests.

#### Scenario: STT operates in airplane mode
- **WHEN** the device is in airplane mode (no internet connectivity)
- **THEN** `WhisperSttEngine` produces a valid transcript for a clear utterance
- **AND** no network-related errors occur

#### Scenario: Final transcript delivered
- **WHEN** a speech segment is processed by `WhisperSttEngine`
- **THEN** the engine emits a final transcript event containing the recognized text
- **AND** the processing completes within an acceptable latency budget (P50 < 2 seconds on target device, validated in Spike 3)

---

### Requirement: `TtsEngine` abstraction
The system SHALL define a `TtsEngine` interface for all text-to-speech output. The default implementation SHALL use Android's built-in TTS engine (`TextToSpeech`).

#### Scenario: TTS speaks response
- **WHEN** the `ConversationManager` delivers a final text response
- **THEN** `TtsEngine` synthesizes and plays the audio through the device speaker or connected audio output

#### Scenario: TTS is cancellable
- **WHEN** TTS playback is in progress and a cancellation signal is received
- **THEN** audio playback stops immediately
- **AND** the `TtsEngine` is ready for a new utterance

---

### Requirement: Pipeline cancellation propagates fully
Any in-progress pipeline stage (VAD, STT inference, TTS playback) SHALL be cancellable and SHALL release resources promptly upon cancellation.

#### Scenario: User re-invokes assistant during TTS
- **WHEN** TTS is playing and the user triggers the assistant again
- **THEN** TTS playback stops
- **AND** the pipeline resets to the listening state for the new utterance

---

### Requirement: Real Whisper transcription output
The STT engine SHALL transcribe recorded PCM audio by executing the loaded LiteRT Whisper model, and the emitted final transcript SHALL reflect the actual recognized speech.

#### Scenario: Transcript varies with speech
- **WHEN** two different utterances are spoken and recorded by the pipeline
- **THEN** the engine emits final transcripts corresponding to the spoken content, which are not identical for different utterances

#### Scenario: Placeholder output is never emitted
- **WHEN** any utterance is transcribed
- **THEN** the emitted transcript is never a fixed constant string (e.g. `"Voice command received"`)

---

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

---

### Requirement: Strategy-aware STT readiness gating
The voice turn precheck SHALL require STT engine readiness only when the selected voice-input strategy is STT-transcribe. Turns using the direct-audio strategy SHALL NOT be blocked by the STT engine being unavailable or uninitialized.

#### Scenario: Direct-audio turn without STT model loaded
- **WHEN** the active LLM is audio-capable and no STT model is loaded, and the user invokes the voice assistant
- **THEN** the turn proceeds with direct audio input instead of failing with an STT readiness error

---

### Requirement: STT transcribes in the configured language
`transcribeAudio` SHALL accept a language parameter defaulting to `"auto"` and SHALL pass it to the whisper bridge, where `"auto"` triggers language auto-detection. Both STT paths (chat STT provisioning and the assistant's STT_TRANSCRIBE strategy) SHALL pass the configured conversation language.

#### Scenario: Locked language transcribed
- **WHEN** `conversationLanguage = "hi"` and the user speaks Hindi in the STT_TRANSCRIBE path
- **THEN** the whisper call receives `"hi"` rather than the bridge's `"en"` default

#### Scenario: Auto-detect
- **WHEN** `conversationLanguage = "auto"`
- **THEN** the whisper call receives `"auto"` and detection runs per utterance

---

### Requirement: TTS speaks in the configured language
`AndroidTtsEngine` SHALL NOT pin a hardcoded locale. It SHALL expose `configureLanguage(bcp47Tag)`, apply it at init and on agent-config change, resolving `"auto"`/null to the device default locale and degrading without crash when the platform TTS lacks the requested voice.

#### Scenario: TTS follows the setting
- **WHEN** `conversationLanguage = "es"` and the assistant speaks a response
- **THEN** the TTS engine's locale is Spanish for that utterance

#### Scenario: Missing system voice degrades gracefully
- **WHEN** the configured language's voice is not installed on the device
- **THEN** TTS falls back to its default voice and logs a warning
- **AND** the app does not crash or silently drop the utterance

---

### Requirement: TTS engine initialization
`AndroidTtsEngine` SHALL resolve its initial TTS locale through `configureLanguage` (device default when unconfigured) instead of hardcoding `Locale.US`.

#### Scenario: Engine init without configuration
- **WHEN** the TTS engine initializes before any language setting is applied
- **THEN** it uses the device default locale rather than `Locale.US`

---

### Requirement: Multi-turn follow-up capture is strategy-aware

The assistant's follow-up loop SHALL route each captured follow-up utterance according to
the resolved voice-input strategy. On the STT-transcribe strategy the loop SHALL transcribe
before sending text; on the direct-audio strategy the loop SHALL convert the captured PCM
to WAV and send it as audio bytes with an empty user-input string. The loop SHALL NOT rely
on STT availability to process a captured utterance when the strategy is direct-audio. The
follow-up loop SHALL use TTS-gated microphone capture so the assistant's own spoken tail is
not ingested as user speech.

**Mic re-arm SHALL be driven exclusively by the model's structured turn-intent signal
(`ask_user` tool invocation). The app SHALL NOT inspect response prose to decide whether to
listen — response text shape (question marks, phrasing, punctuation) SHALL NOT trigger or
suppress microphone re-arm. The follow-up loop SHALL NOT be bounded by a small round cap; a
generous safety limit (>= 10 rounds) MAY exist to guard against runaway loops.**

#### Scenario: Direct-audio follow-up with Whisper inactive

- **WHEN** the active model is audio-capable (direct-audio), Whisper is not loaded, and the
  model ends its turn with `ask_user`
- **THEN** the captured reply is sent as WAV audio bytes with an empty user-input string

#### Scenario: STT-transcribe follow-up unchanged

- **WHEN** the active model is text-only (STT-transcribe) and the model ends its turn with
  `ask_user`
- **THEN** the captured reply is transcribed and sent as text

#### Scenario: Silent follow-up

- **WHEN** the follow-up capture contains no speech (silent buffer) under either strategy
- **THEN** the round expires via the confirmation timeout with a graceful sign-off and the
  pending state is cleared

#### Scenario: Question prose without intent signal

- **WHEN** the model's final response ends with a question mark but the turn did NOT end
  with an `ask_user` invocation
- **THEN** the microphone SHALL NOT re-arm and the turn completes in the terminal state
- **AND** a DEBUG-level diagnostic is logged so protocol adherence is observable

#### Scenario: Mid-string question

- **WHEN** the model's final response contains a question mid-string (e.g. "Which contact
  would you like to call? I see ... and Rushikesh's Mom.") and ends with `ask_user`
- **THEN** the microphone re-arms — text shape is irrelevant to the decision

