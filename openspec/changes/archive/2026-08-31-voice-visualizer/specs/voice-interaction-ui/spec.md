## ADDED Requirements

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

### Requirement: Equalizer state visuals
The overlay visualizer SHALL convey state through its animation: Listening bars react to amplitude, Processing bars pulse gently, Speaking bars wave slowly, and Idle bars are static and low. State SHALL remain clearly communicated without relying on the equalizer alone (state text/icon stays).

#### Scenario: Processing and Speaking render distinct motion
- **WHEN** the assistant transitions from Listening to Processing and then to Speaking
- **THEN** the equalizer animates distinctly (reactive → gentle pulse → slow wave) for each state

### Requirement: Vector icons in the overlay
The voice overlay SHALL render its status glyphs (listening mic, speaking speaker, error warning) as Material vector icons rather than unicode text glyphs, consistent with the app's icon rule.

#### Scenario: Overlay icons are vector
- **WHEN** the overlay displays listening/speaking/error status
- **THEN** vector icons are rendered; no unicode glyphs are used