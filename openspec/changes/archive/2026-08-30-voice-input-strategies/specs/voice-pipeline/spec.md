## ADDED Requirements

### Requirement: Strategy-aware STT readiness gating
The voice turn precheck SHALL require STT engine readiness only when the selected voice-input strategy is STT-transcribe. Turns using the direct-audio strategy SHALL NOT be blocked by the STT engine being unavailable or uninitialized.

#### Scenario: Direct-audio turn without STT model loaded
- **WHEN** the active LLM is audio-capable and no STT model is loaded, and the user invokes the voice assistant
- **THEN** the turn proceeds with direct audio input instead of failing with an STT readiness error
