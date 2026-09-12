# Delta: audio-input-front-end (NEW)

## ADDED Requirements

### Requirement: DSP-backed input source selection
The voice capture path SHALL request `AudioSource.VOICE_RECOGNITION` when constructing its
`AudioRecord`. If the source fails to initialize, it SHALL fall back to
`AudioSource.MIC` and log the fallback.

#### Scenario: Voice recognition source accepted
- **WHEN** the audio reader is constructed on a device where `VOICE_RECOGNITION`
  initializes successfully
- **THEN** the `AudioRecord` is created with `AudioSource.VOICE_RECOGNITION`

#### Scenario: Fallback to raw mic
- **WHEN** the `AudioRecord` built with `VOICE_RECOGNITION` does not reach
  `STATE_INITIALIZED`
- **THEN** the reader retries with `AudioSource.MIC`, logs a warning, and capture proceeds

### Requirement: Platform DSP effect attachment
The reader SHALL attach `NoiseSuppressor` and `AcousticEchoCanceler` (`AEC`) to the active audio session when each reports `isAvailable()` and creation succeeds, and SHALL release any attached effects when the recorder is torn down. Attaching `AcousticEchoCanceler` SHALL prevent device speaker output from bleeding back into the microphone during armed capture or multi-round dialogue. `AutomaticGainControl` SHALL be disabled by default.

#### Scenario: Effects attached where supported
- **GIVEN** a device where `NoiseSuppressor.isAvailable()` and `AcousticEchoCanceler.isAvailable()` return true
- **WHEN** recording starts
- **THEN** both effects are created on the recorder's audio session ID and enabled

#### Scenario: Acoustic echo cancellation suppresses speaker playback
- **GIVEN** the microphone is armed while TTS is playing or completing
- **WHEN** audio is captured during playback
- **THEN** `AcousticEchoCanceler` cancels device speaker acoustic feedback from the PCM input buffer

#### Scenario: Effects released on teardown
- **WHEN** the recorder is stopped/released
- **THEN** every attached effect is released and no effect outlives the session

### Requirement: Front-end observability
The reader SHALL log the resolved front-end configuration (source, ns/aec/agc booleans) once per recorder lifetime, so capture characteristics can be diagnosed in logs.

#### Scenario: Configuration logged
- **WHEN** the reader starts recording
- **THEN** a single log line states the chosen source and the attached effects: `[Loki/AudioFrontEnd] source=<src> ns=<bool> aec=<bool> agc=<bool>`

### Requirement: VAD knob verification with DSP input
Because a DSP source changes the acoustic background floor, this capability SHALL verify onset, end-of-speech, and short-utterance behavior with the new pipeline, ensuring absolute and relative thresholds in `AudioRecorder.kt` operate cleanly on-device.

#### Scenario: Onset and end-of-speech validated post-DSP
- **WHEN** speech is captured against the DSP-enabled pipeline
- **THEN** speech onset triggers on sustained first-word energy (≥250ms), capture ends within the configured silence window after speech ceases, and short utterances (≥350ms) are preserved

