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

---

### Requirement: Live amplitude-driven equalizer during listening
While the assistant is in the Listening state, the overlay SHALL render a Gemini-style animated bar equalizer driven by the live microphone amplitude (RMS) of the active recorder, so the user sees that the mic is live and how loudly they are speaking. The equalizer SHALL be driven by the same amplitude source for both DIRECT_AUDIO and STT_TRANSCRIBE strategies, SHALL animate smoothly (throttled to ~30fps), and SHALL use theme colors.

#### Scenario: Direct-audio listening shows live level
- **WHEN** the assistant is listening with DIRECT_AUDIO and the user speaks louder
- **THEN** the equalizer bars grow in response to the measured RMS in real time

#### Scenario: STT listening shows live level
- **WHEN** the assistant is listening with STT_TRANSCRIBE and audio is being captured
- **THEN** the equalizer bars animate with the captured amplitude, before or during transcription

#### Scenario: Silence keeps bars low
- **WHEN** the user is silent during listening
- **THEN** the equalizer settles near its minimum amplitude

---

### Requirement: Equalizer state visuals
The overlay visualizer SHALL convey state through its animation: Listening bars react to amplitude, Processing bars pulse gently, Speaking bars wave slowly, and Idle bars are static and low. State SHALL remain clearly communicated without relying on the equalizer alone (state text/icon stays).

#### Scenario: Processing and Speaking render distinct motion
- **WHEN** the assistant transitions from Listening to Processing and then to Speaking
- **THEN** the equalizer animates distinctly (reactive -> gentle pulse -> slow wave) for each state

---

### Requirement: Vector icons in the overlay
The voice overlay SHALL render its status glyphs (listening mic, speaking speaker, error warning) as Material vector icons rather than unicode text glyphs, consistent with the app's icon rule.

#### Scenario: Overlay icons are vector
- **WHEN** the overlay displays listening/speaking/error status
- **THEN** vector icons are rendered; no unicode glyphs are used

---

### Requirement: Audio start cue for voice input
When a voice input session begins (chat mic press or assistant long-press), the system SHALL play a short (~250 ms), non-speech, Gemini-like rising attention tone BEFORE microphone recording / listening begins. The cue SHALL be synthesized at runtime (no bundled asset), SHALL play exactly once per session, and SHALL NOT play when the session ends, when a turn completes, or on configuration changes. A hidden `audioStartCueEnabled` flag SHALL gate playback (default true).

#### Scenario: Chat mic plays start cue
- **WHEN** the user presses the chat mic
- **THEN** the attention tone plays before the STT/recording path starts

#### Scenario: Assistant long-press plays start cue
- **WHEN** the user long-presses the assistant trigger
- **THEN** the attention tone plays as the voice overlay begins listening

#### Scenario: Cue does not repeat or play on stop
- **WHEN** voice recording is already active or the session ends
- **THEN** no additional tone is played
