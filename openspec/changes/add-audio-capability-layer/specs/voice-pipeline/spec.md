# Delta: voice-pipeline (MODIFIED)

## MODIFIED Requirements

### Requirement: Local microphone capture during session
The voice pipeline SHALL capture audio from the device microphone exclusively during an
active `VoiceInteractionSession`. No background audio recording SHALL occur outside of an
active session. The capture path SHALL request a DSP-backed input source
(`VOICE_RECOGNITION`) with platform noise-suppression effects when the device provides
them, falling back to the raw `MIC` source otherwise.

#### Scenario: Microphone opens on session start
- **WHEN** a `VoiceInteractionSession` becomes active
- **THEN** the pipeline opens the microphone and begins capturing audio
- **AND** audio is processed locally without transmission to any remote server

#### Scenario: Microphone closes on session end
- **WHEN** the session is hidden or cancelled
- **THEN** microphone recording stops immediately
- **AND** the audio resource is released

#### Scenario: DSP-backed capture on capable device
- **WHEN** the session opens the microphone on a device supporting the
  `VOICE_RECOGNITION` source
- **THEN** the capture pipeline uses that source rather than raw `MIC`
