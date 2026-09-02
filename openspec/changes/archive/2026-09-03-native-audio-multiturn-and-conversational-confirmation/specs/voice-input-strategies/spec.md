## MODIFIED Requirements

### Requirement: Direct-audio strategy sends recorded audio to the LLM
The direct-audio strategy SHALL record microphone audio (16 kHz PCM 16-bit, maximum 30 seconds), package it as a WAV byte array, and send it to the LLM as a `Content.AudioBytes` turn together with the user's text prompt. The audio turn SHALL participate in the same ReAct JSON tool-calling loop as text turns. This routing SHALL apply to EVERY round of a multi-turn voice interaction — the initial utterance, disambiguation and clarification follow-ups, and conversational confirmation replies — whenever the resolved strategy is direct-audio. The STT engine SHALL NOT be consulted for transcription or verdict parsing on any direct-audio round.

#### Scenario: Voice command routed as audio
- **WHEN** a user speaks a command with an audio-capable model active
- **THEN** the recorded audio is delivered to the LLM as audio content within the tool-calling loop and the assistant produces a spoken response

#### Scenario: Recording duration is capped
- **WHEN** a user speaks longer than 30 seconds with an audio-capable model active
- **THEN** recording stops at 30 seconds and the captured audio is processed

#### Scenario: Follow-up reply routed as audio
- **WHEN** the assistant asks a follow-up question (e.g. a disambiguation list) with an audio-capable model active, and the user replies verbally
- **THEN** the reply audio is encoded as WAV and passed directly to the LLM as an audio turn with source `VOICE_FOLLOW_UP`
- **AND** the Whisper STT engine is not invoked for that round

#### Scenario: Follow-up reply with no STT engine loaded
- **WHEN** the active strategy is direct-audio and no STT model is loaded, and the user replies to a follow-up question
- **THEN** the reply is processed via native audio and is NOT treated as missed speech
