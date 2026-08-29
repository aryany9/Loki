# Design: Agent Playground & Model Configuration UI

## Overview

This design specifies the Compose UI, state management, and configuration persistence for the **Agent Playground & Model Configuration** screen. It bridges user configuration preferences to the `AgentConfig` data model established in Change 2 (`engine-capabilities`).

## UI Screen Layout & Flow

```text
┌─────────────────────────────────────────────────────────────────┐
│ ⚙️ Model & Agent Configuration                                  │
├─────────────────────────────────────────────────────────────────┤
│ SELECTED MODEL                                                  │
│ Name: Gemma-4-E4B-it.litertlm           Status: LOADED          │
│ Format: LiteRT-LM                       Context: 8192 tokens    │
│ Capabilities: [✓ Text]  [✓ Tool Calling]  [✓ Audio]  [✗ Vision] │
│                                                                 │
│                                           [ Change Model ] ──▶ (To Model Library)
├─────────────────────────────────────────────────────────────────┤
│ AGENT INSTRUCTIONS                                              │
│ System Prompt:                                                  │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ You are Loki, a private offline Android assistant...       │ │
│ └─────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ GENERATION & HYPERPARAMETERS                                    │
│ Temperature:          [ 0.70 ]  ── Slider (0.0 - 2.0)          │
│ Top-K:                [ 40   ]  ── Slider (1 - 100)           │
│ Top-P:                [ 0.95 ]  ── Slider (0.0 - 1.0)          │
│ Seed:                 [ Optional ]                              │
│ Max Output Tokens:    [ Optional ]                              │
│                                                                 │
│ Execution Backend:    (•) Automatic   ( ) GPU   ( ) CPU         │
├─────────────────────────────────────────────────────────────────┤
│ SPEECH & VOICE PROVIDERS                                        │
│ ASR Engine:           (•) LiteRT Whisper STT                    │
│ TTS Provider:         (•) Android System TTS   ( ) Custom Model │
├─────────────────────────────────────────────────────────────────┤
│ TEST PROMPT RUNNER                                              │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ What time is it in Tokyo?                                   │ │
│ └─────────────────────────────────────────────────────────────┘ │
│ [ RUN TEST PROMPT ]                                             │
│ Response: "The current time is ..."                             │
├─────────────────────────────────────────────────────────────────┤
│   [ RESET DEFAULTS ]                       [ SAVE CONFIG ]      │
└─────────────────────────────────────────────────────────────────┘
```

## Data Persistence & State Management

- **ViewModel**: `AgentPlaygroundViewModel` collects active `ModelRecord` from `ModelLibraryManager` and `AgentConfig` from `DataStorePreferences`.
- **Save Action**: Validates input bounds (e.g., temperature in `0.0..2.0`, topK in `1..100`), persists updated `AgentConfig` to DataStore, and notifies `ConversationManager`.
- **Test Prompt**: Executes a single-turn `generate()` call against `LlmEngine` using current Playground parameters, displaying raw model token outputs and tool execution diagnostics.
