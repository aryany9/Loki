# Design: End-to-End Voice Assistant Workflow & LiteRT Whisper Integration

## Overview

This design specifies the end-to-end Voice Assistant production workflow for Loki, standardizing STT on a LiteRT-based Whisper model (`LiteRtWhisperEngine`), isolating Voice Layer, Agent Layer, Tool System, and TTS Layer into clean decoupled abstractions, enforcing single-turn voice sessions (`maxTurns = 1`), and implementing multi-stage cancellation.

## System Architecture & Layer Boundaries

```text
                               Loki Voice Assistant
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 │                                             │
            Voice Layer                                   Agent Layer
                 │                                             │
                 ▼                                             ▼
        LiteRtWhisperEngine                           LiteRtLlmEngine
     (LiteRT Whisper .tflite)                       (LiteRT-LM Gemma/Qwen)
                 │                                             │
                 │ Transcript                                  │ Response / Tools
                 └──────────────────────┬──────────────────────┘
                                        │
                                 Tool Execution
                                        │
                                        ▼
                                  Final Response
                                        │
                                        ▼
                                   TTS Provider
                                 ┌──────┴──────┐
                                 │             │
                          Android System   Custom Local
                               TTS              TTS
                                 │             │
                                 └──────┬──────┘
                                        ▼
                                 Audio Playback
```

## Detailed Voice Interaction Pipeline

```text
 [ Invocation ] ──▶ [ Audio Capture & VAD ] ──▶ [ LiteRtWhisper STT ] ──▶ [ Voice Agent Session ]
                                                                                   │
                                                                                   ▼
 [ Safe Idle ] ◀── [ Audio Playback ] ◀── [ Selected TTS ] ◀── [ Answer ] ◀── [ LiteRT-LM & Tools ]
```

### 1. LiteRT STT Integration (`LiteRtWhisperEngine`)
- Implements `SttEngine` interface (`isListening`, `startListening(): Flow<SttEvent>`, `stopListening()`, `cancel()`, `release()`).
- Consumes `AudioRecorder.recordUtterance()` 16kHz PCM `FloatArray`.
- Applies fixed 30-second window padding/preprocessing, runs LiteRT `encode` and `decode` token signatures, and emits `SttEvent.FinalResult(transcript)`.
- Whisper `.tflite` model stored in Loki's managed Model Library (`files/models/stt/`).

### 2. Single-Turn Voice Mode Policy
- `ConversationManager.newVoiceSession()` creates a `ConversationSession` scoped to `maxTurns = 1`.
- Does not auto-inject long-term ChatScreen conversation history, optimizing turn turnaround latency.

### 3. Multi-Stage Cancellation Pipeline
- `AssistantSession.cancelTurn()` immediately cancels active jobs:
  - **STT**: Stops `AudioRecorder` and cancels coroutine flow.
  - **LLM**: Invokes `LiteRtLlmEngine.cancel()` (`activeConversation?.cancelProcess()`).
  - **TTS**: Calls `TtsEngine.stop()` (`android.speech.tts.TextToSpeech.stop()`).
  - **Overlay**: Transitions `AssistantState` back to `Idle`.
