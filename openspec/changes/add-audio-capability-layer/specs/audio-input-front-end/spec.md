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
The reader SHALL attach `NoiseSuppressor` and `AcousticEchoCanceler` to the active audio
session when each reports `isAvailable()` and creation succeeds, and SHALL release any
attached effects when the recorder is torn down. `AutomaticGainControl` SHALL be disabled
by default.

#### Scenario: Effects attached where supported
- **GIVEN** a device where `NoiseSuppressor.isAvailable()` returns true
- **WHEN** recording starts
- **THEN** a `NoiseSuppressor` is created on the recorder's audio session and enabled

#### Scenario: Effects released on teardown
- **WHEN** the recorder is stopped/released
- **THEN** every attached effect is released and no effect outlives the session

### Requirement: Front-end observability
The reader SHALL log the resolved front-end configuration (source, ns/aec/agc booleans)
once per recorder lifetime, so VAD logs can be attributed to a known capture pipeline.

#### Scenario: Configuration logged
- **WHEN** the reader starts recording
- **THEN** a single log line states the chosen source and the attached effects

### Requirement: VAD knob recalibration with DSP input
Because a DSP source changes the RMS distribution, this capability SHALL include an
on-device tuning pass validating onset, end-of-speech, and short-utterance behavior with
the new pipeline, and SHALL update absolute RMS floor constants if required. Relative
(× noiseFloor) factors remain unchanged unless device logs prove otherwise.

#### Scenario: Onset and end-of-speech validated post-DSP
- **WHEN** the tuning pass runs against the DSP-enabled pipeline
- **THEN** speech onset triggers on first-word energy, capture ends within the configured
  silence window after speech ceases, and short utterances (≥350ms) are preserved
