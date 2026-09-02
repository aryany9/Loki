## MODIFIED Requirements

### Requirement: System prompt includes tool descriptions and disabled-tool notices
The system prompt SHALL be split into a KV-prefilled core prompt and per-turn composed content. The core prompt SHALL contain persona, the JSON output protocol, the language directive, and memories — and SHALL NOT contain tool schemas or capability instructions. The per-turn composition SHALL include: (1) the schema list of the currently visible tools (all permission-granted tools when no capability is active; `general` + active-capability tools when one is), (2) capability instructions injected in full on the activation turn and as a compact reminder while active, (3) a task-state block rendered fresh from the session's `TaskState` when present, and (4) disabled-tool notices where relevant. Nothing derived from runtime task state SHALL be baked into the KV-prefilled core prompt.

#### Scenario: Available tools appear in per-turn composition
- **WHEN** `get_current_time` and `get_battery_status` are available and no capability is active
- **THEN** the per-turn composition includes descriptive entries for both tools

#### Scenario: Scoped tool schemas during an active capability
- **WHEN** the `calling` capability is active
- **THEN** the per-turn composition lists only `general` and `calling` tool schemas
- **AND** the KV-prefilled core prompt remains unchanged

#### Scenario: Task state rendered fresh each turn
- **WHEN** a `ContactResolution` task state is pending with 6 candidates
- **THEN** the per-turn composition contains the candidate names and app-issued IDs
- **AND** no phone numbers appear anywhere in the model's context

#### Scenario: Disabled tool appears in notice with reason
- **WHEN** `CALL_PHONE` is not granted
- **THEN** the composition lists `call_contact` under "Disabled tools: Requires CALL_PHONE permission"

## ADDED Requirements

### Requirement: Capability activation and deactivation lifecycle
`ConversationSession` SHALL track an `activeCapability` derived from the model's tool calls: calling (or receiving a result from) a tool of capability C with no conflicting pending task state activates C; the capability deactivates when its task state completes (or is absent and a final response is produced) or on cancellation. No keyword matching, intent classifier, or separate routing model SHALL be used. Capability instructions SHALL be injected into the per-turn composition on activation.

#### Scenario: Tool call activates its capability
- **WHEN** the model emits `lookup_contact` with no capability active
- **THEN** the `calling` capability activates
- **AND** the next per-turn composition carries the calling instructions and scoped tool set

#### Scenario: Task completion deactivates
- **WHEN** the active capability's task state resolves and the model produces a final response
- **THEN** the capability deactivates
- **AND** the next turn's composition returns to the full tool set with core prompt only

#### Scenario: Pending state blocks silent capability switch
- **WHEN** a task state is unresolved and the model emits a tool call of a different capability
- **THEN** the call is not executed
- **AND** a coached tool-result turn directs the model to resolve the current task first

### Requirement: Application-owned task state with validated tool calls
`ConversationSession` SHALL own a typed task state (sealed `TaskState`) for multi-turn tool flows, starting with contact resolution. Each state variant SHALL expose `advancingTool: String?` derived from its own fields — the tool that can resolve or complete the state now — and `resolved: Boolean`. The application SHALL validate every advancing tool call against the live state (ID membership, confirmation prerequisites) before execution, and SHALL resolve identities (e.g. phone numbers) from its own data, never from model output. Phone numbers and other sensitive application facts SHALL NOT be placed in model context during task state. Task-state completion SHALL deactivate the capability.

#### Scenario: Candidate selection validated
- **WHEN** the model selects a candidate ID during `ContactResolution`
- **THEN** the session accepts it only if the ID matches a live candidate
- **AND** an invalid ID produces a corrective tool-result turn re-rendering the state

#### Scenario: Advancing tool remains callable while state is pending
- **WHEN** a task state is pending
- **THEN** the state's `advancingTool` is included in the scoped visible tool set
- **AND** the model always has a legal next move

#### Scenario: Call executes with app-resolved identity
- **WHEN** the user verbally confirms and the model emits the advancing call tool
- **THEN** the session validates the confirmed state, resolves the contact ID to the real phone number from its own lookup data, and executes `call_contact`
- **AND** the spoken response names the contact, never reading the raw number
