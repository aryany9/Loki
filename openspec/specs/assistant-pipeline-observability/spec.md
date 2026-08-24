### Requirement: TurnLogger assigns a unique ID to each assistant turn
`TurnLogger` SHALL generate a random UUID for each assistant turn and attach it to every log entry for that turn.

#### Scenario: Each turn has a distinct ID
- **WHEN** two consecutive assistant turns are processed
- **THEN** each turn's log entries carry a different UUID prefix

### Requirement: TurnLogger logs all pipeline stages in INFO level
`TurnLogger` SHALL emit `Log.i` entries with tag `LokiTurn` for each of the following stages: input source (VOICE/TEXT), Whisper transcript (if voice), available and disabled tool counts, LLM raw output, parsed result type, permission check outcome, and final response text.

#### Scenario: Full turn is traceable from logcat
- **WHEN** a voice turn is processed end-to-end
- **THEN** `adb logcat -s LokiTurn` shows all pipeline stages for that turn in chronological order under the same UUID

### Requirement: Full prompt is logged only in debug builds
`TurnLogger` SHALL emit the complete prompt string (including all conversation history) with `Log.d` (DEBUG level) only when `BuildConfig.DEBUG` is `true`. Release builds SHALL NOT log prompt content.

#### Scenario: Prompt visible in debug build
- **WHEN** the app is built in debug mode and a turn is processed
- **THEN** `adb logcat -s LokiTurn` shows the full prompt under the turn UUID

#### Scenario: Prompt not visible in release build
- **WHEN** the app is built in release mode and a turn is processed
- **THEN** `adb logcat -s LokiTurn` does NOT show prompt content; only metadata is logged

### Requirement: TurnLogger logs token count and grammar size
`TurnLogger` SHALL log the tokenized prompt token count and the GBNF grammar byte size at INFO level for each turn (sourced from native logs that already produce this data).

#### Scenario: Token count visible in logs
- **WHEN** a turn is processed
- **THEN** the turn log shows how many tokens the prompt was tokenized into
