# Spec: Agent Playground & Model Configuration UI

## ADDED Requirements

### Requirement: Model & Agent Configuration UI

The system MUST provide a dedicated configuration screen displaying selected model status, capability flags, editable system prompt, generation hyperparameters, execution backend selection, and speech provider controls.

#### Scenario: User adjusts generation hyperparameters and saves
- **GIVEN** the Agent Playground screen is open
- **WHEN** the user modifies the temperature slider to 0.3 and topK to 20, then taps "Save Configuration"
- **THEN** the updated `AgentConfig` is validated and persisted to app preferences.

### Requirement: Test Prompt Execution

The system MUST provide a live test prompt runner within the Agent Playground allowing real-time prompt testing against the active model configuration.

#### Scenario: Running a test prompt in Playground
- **GIVEN** an active loaded model and customized system prompt
- **WHEN** the user types "What is the capital of France?" in the test prompt input and taps "Run Test Prompt"
- **THEN** the model output response and tool execution diagnostics are displayed in the playground output area.

### Requirement: Provider Selection Controls

The configuration UI MUST present selectors for ASR (LiteRT Whisper STT vs System) and TTS providers (Android System TTS vs Custom Local Model).

#### Scenario: Selecting TTS Provider
- **GIVEN** the Speech & Voice Providers section in Agent Playground
- **WHEN** the user toggles between Android System TTS and Custom Local Model
- **THEN** the selected TTS provider setting is updated in voice configuration state.
