## ADDED Requirements

### Requirement: Nine MVP local tools implemented
The system SHALL implement the following nine local Android tools as `LocalTool` implementations registered in `ToolRegistry`:

1. `lookup_contact` — search contacts by name
2. `call_contact` — initiate a phone call to a contact by id
3. `dial_number` — initiate a phone call to an arbitrary phone number
4. `open_app` — launch an installed application by name or package
5. `get_battery_status` — return current battery level and charging state
6. `get_current_time` — return current time and date
7. `set_timer` — set a countdown timer for a specified duration
8. `set_alarm` — set an alarm for a specified time
9. `media_control` — control media playback (play, pause, next, previous)

All nine tools SHALL function without internet connectivity.

#### Scenario: Call contact by name
- **WHEN** the user says "Call Rahul" and `lookup_contact(name="Rahul")` finds exactly one match
- **THEN** `call_contact(contactId=<id>)` is invoked
- **AND** an outgoing phone call is initiated

#### Scenario: Open application by name
- **WHEN** the user says "Open YouTube Music"
- **THEN** `open_app(name="YouTube Music")` resolves the package name
- **AND** the app launches in the foreground

#### Scenario: Get battery status
- **WHEN** `get_battery_status` is executed
- **THEN** it returns `{"level": <0-100>, "charging": <true|false>}` using Android's `BatteryManager`

#### Scenario: Set a timer
- **WHEN** the user says "Set a timer for 10 minutes"
- **THEN** `set_timer(durationSeconds=600)` creates a countdown timer
- **AND** the user receives confirmation via TTS

#### Scenario: Media control — pause
- **WHEN** the user says "Pause the music"
- **THEN** `media_control(action="PAUSE")` sends a media session pause command
- **AND** the active media player pauses

---

### Requirement: Tools request permissions on demand
Each local tool SHALL request the minimum necessary Android permission at the time the tool is first invoked, not at application startup.

#### Scenario: First call to call_contact triggers permission request
- **WHEN** `call_contact` is invoked for the first time and `CALL_PHONE` has not been granted
- **THEN** Android's permission request dialog appears
- **AND** upon grant, the tool retries execution
- **AND** upon denial, a `ToolResult` with `reason=PERMISSION_DENIED` is returned

---

### Requirement: Tools return structured `ToolResult`
Every tool implementation SHALL return a `ToolResult` with structured JSON data. No tool SHALL generate natural-language response text.

#### Scenario: lookup_contact returns structured matches
- **WHEN** `lookup_contact(name="Rahul")` finds two contacts
- **THEN** the `ToolResult.data` is `{"matches": [{"id": "...", "name": "Rahul Sharma"}, {"id": "...", "name": "Rahul Verma"}]}`
- **AND** no human-readable sentence is included in the result
