## Tasks

- [ ] 1. Create `AgentPlaygroundViewModel.kt` in `core:ui` to manage `AgentConfig` state, hyperparameter editing, and test prompt execution.
- [ ] 2. Create `AgentPlaygroundScreen.kt` Compose UI in `core:ui` with sections for Selected Model info, System Prompt, Hyperparameter sliders, Backend radio buttons, Speech Provider selectors, and Test Prompt runner.
- [ ] 3. Implement DataStore persistence for `AgentConfig` preferences in `core:llm` or `core:ui`.
- [ ] 4. Add `AGENT_PLAYGROUND` to `AppScreen` navigation enum and add navigation entry points from ChatScreen top bar and Setup screen.
- [ ] 5. Add unit tests for `AgentPlaygroundViewModel` testing parameter validation, saving, resetting defaults, and test prompt execution.
