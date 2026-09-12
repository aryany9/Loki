## ADDED Requirements

### Requirement: Multi-turn follow-up capture is strategy-aware
The assistant's follow-up loop SHALL route each captured follow-up utterance according to the resolved voice-input strategy. On the STT-transcribe strategy the loop SHALL transcribe before sending text; on the direct-audio strategy the loop SHALL convert the captured PCM to WAV and send it as audio bytes with an empty user-input string. The loop SHALL NOT rely on STT availability to process a captured utterance when the strategy is direct-audio. The follow-up loop SHALL use TTS-gated microphone capture so the assistant's own spoken tail is not ingested as user speech.

#### Scenario: Direct-audio follow-up with Whisper inactive
- **WHEN** the active model is audio-capable (direct-audio), Whisper is not loaded, and the user verbally replies to the assistant's follow-up question
- **THEN** the captured audio is encoded as WAV and forwarded to the conversation session as an audio turn
- **AND** the assistant does not speak "I didn't catch that" for valid captured speech

#### Scenario: STT-transcribe follow-up unchanged
- **WHEN** the active model is text-only (STT-transcribe) and the user replies to a follow-up question
- **THEN** the reply is transcribed by the STT engine and forwarded as a text turn

#### Scenario: Silent follow-up
- **WHEN** the follow-up capture contains no speech (silent buffer) under either strategy
- **THEN** the loop applies its existing retry-then-exit behavior without invoking the LLM
