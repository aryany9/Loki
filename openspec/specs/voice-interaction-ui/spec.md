## Purpose
Voice interaction overlay UI for assistant sessions.

## Requirements

### Requirement: Minimal Compose overlay rendered within VoiceInteractionSession
The system SHALL render a minimal Jetpack Compose UI overlay within the `VoiceInteractionSession` window. The overlay SHALL NOT be a full-screen activity. It SHALL display the current session state without obstructing the device display unnecessarily.

#### Scenario: Overlay appears on session start
- **WHEN** a `VoiceInteractionSession` starts
- **THEN** the Compose overlay appears on screen (or over the lock screen where permitted)
- **AND** it shows the Loki identity (name/avatar) and a "Listening…" indicator

---

### Requirement: Session state reflected in UI
The overlay SHALL update to reflect the current pipeline state: Listening, Processing, Speaking, and Error.

#### Scenario: State transitions visible to user
- **WHEN** the pipeline moves from listening → STT processing → LLM inference → TTS
- **THEN** the overlay transitions through: "Listening…" → "Processing…" → response text or speaking indicator
- **AND** each state is visually distinct

---

### Requirement: Partial transcript shown during STT
The overlay SHALL display the partial or final transcript of the user's spoken input while STT is processing.

#### Scenario: User speech shown in overlay
- **WHEN** the STT engine emits a partial or final transcript event
- **THEN** the recognized text appears in the overlay
- **AND** the text updates as the transcript progresses

---

### Requirement: Response text displayed
The overlay SHALL display the text of Loki's response when the assistant is speaking via TTS.

#### Scenario: Response text shown during TTS playback
- **WHEN** the `TtsEngine` begins speaking a response
- **THEN** the response text appears in the overlay
- **AND** the text remains visible until the session ends or transitions to the next listening state

---

### Requirement: Overlay does not require device unlock
Where Android permits, the session overlay SHALL be visible and interactable while the device is locked, without requiring the user to unlock the device.

#### Scenario: Overlay accessible on lock screen
- **WHEN** the session is invoked from the lock screen
- **THEN** the Compose overlay renders over the keyguard
- **AND** the user can interact with the session (hear TTS, trigger new turn) without unlocking
