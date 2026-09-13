## ADDED Requirements

### Requirement: Accelerated local STT
The default `SttEngine` implementation SHALL reduce end-of-utterance transcription stalls through on-device acceleration that is safe for the Whisper artifact — the int8 quantized artifact with XNNPACK dispatch disabled (composite-subgraph ops crash natively under XNNPACK).

#### Scenario: Voice turn on the STT transcribe strategy
- **WHEN** a voice turn resolves to the `STT_TRANSCRIBE` strategy on a mid-range device
- **THEN** transcription of a typical utterance completes measurably faster than the full-precision, non-quantized baseline
- **AND** the transcribed text matches the previous full-precision output on a fixed utterance set
