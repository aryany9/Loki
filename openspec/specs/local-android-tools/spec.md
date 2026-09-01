## Purpose
Local on-device tool implementations for Android system actions.

## Requirements

### Requirement: Fifteen local tools implemented
The system SHALL implement the following fifteen local Android tools as `LocalTool` implementations registered in `ToolRegistry`:

1. `lookup_contact` — search contacts by name
2. `call_contact` — initiate a phone call to a contact by id
3. `dial_number` — initiate a phone call to an arbitrary phone number
4. `open_app` — launch an installed application by name or package
5. `get_battery_status` — return current battery level and charging state
6. `get_current_time` — return current time and date
7. `set_timer` — set a countdown timer for a specified duration
8. `set_alarm` — set an alarm for a specified time
9. `media_control` — control media playback (play, pause, next, previous)
10. `toggle_flashlight` — turn device flashlight (torch) on or off
11. `open_wifi_settings` — open system Wi-Fi settings screen
12. `open_bluetooth_settings` — open system Bluetooth settings screen
13. `get_wifi_state` — check whether Wi-Fi is currently enabled
14. `get_bluetooth_state` — check whether Bluetooth is currently enabled
15. `get_ram_usage` — report system RAM usage statistics

All fifteen tools SHALL function without internet connectivity.

#### Scenario: Call contact by name
- **WHEN** the user says "Call Rahul" and `lookup_contact(name="Rahul")` finds exactly one match
- **THEN** the confirmation repeat-back "Call Rahul at +91 ...?" is presented to the user
- **AND** upon affirmative verdict, `call_contact` executes and an outgoing phone call is initiated

#### Scenario: Direct call is denied or unanswered
- **WHEN** the user denies the confirmation, or the confirmation times out
- **THEN** no call is placed
- **AND** the model is informed the user declined or did not respond

#### Scenario: Dial number pre-fill requires no confirmation
- **WHEN** the user asks to dial a number (e.g. `dial_number(phone_number="12345")`)
- **THEN** the dialer opens immediately with the number pre-filled without a confirmation step

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

---

### Requirement: Device-control tools implemented
The system SHALL implement `toggle_flashlight`, `open_wifi_settings`, `open_bluetooth_settings`, `get_wifi_state`, `get_bluetooth_state`, and `get_ram_usage` as `LocalTool` implementations registered in `ToolRegistry`.

#### Scenario: Flashlight toggled directly
- **WHEN** the model invokes `toggle_flashlight(enabled=true)` on a device with a flash unit
- **THEN** the tool probes camera characteristics for `FLASH_INFO_AVAILABLE`, calls `CameraManager.setTorchMode(id, true)`, and returns a success result
- **AND** the torch is physically on

#### Scenario: Flashlight unavailable
- **WHEN** `toggle_flashlight` runs on a device without a flash unit, or the torch call fails
- **THEN** a `ToolResult` with `success=false` and the platform error message is returned
- **AND** the model can inform the user the flashlight is unavailable

#### Scenario: WiFi change handed to the OS panel
- **WHEN** the model invokes `open_wifi_settings`
- **THEN** the system WiFi settings screen opens via `Settings.ACTION_WIFI_SETTINGS`
- **AND** the tool result reports success after the panel resolves (the user completes the actual toggle)

#### Scenario: Bluetooth change handed to the OS panel
- **WHEN** the model invokes `open_bluetooth_settings`
- **THEN** the system Bluetooth settings screen opens via `Settings.ACTION_BLUETOOTH_SETTINGS`
- **AND** no programmatic enable/disable of the radio is attempted

---

### Requirement: Connectivity state queries are read-only and never prompt
`get_wifi_state` and `get_bluetooth_state` SHALL report current radio state without requesting permissions or side effects. When the Bluetooth state cannot be read due to a missing `BLUETOOTH_CONNECT` grant (API 31+), the tool SHALL return an `enabled="unknown"` result with a reason instead of triggering a permission request.

#### Scenario: WiFi state answered
- **WHEN** the user asks whether WiFi is on and the model invokes `get_wifi_state`
- **THEN** the result contains the current enabled state with no permission interaction

#### Scenario: Bluetooth state without permission
- **WHEN** `get_bluetooth_state` runs without `BLUETOOTH_CONNECT` granted on API 31+
- **THEN** the result reports `enabled="unknown"` with the reason `permission_not_granted`
- **AND** no permission dialog is raised

---

### Requirement: RAM usage reported from system memory info
`get_ram_usage` SHALL return total memory, available memory, used percentage, the low-memory flag, and the system low-memory threshold from `ActivityManager.MemoryInfo`, requiring no permission and excluding the assistant's own process heap.

#### Scenario: RAM question answered
- **WHEN** the model invokes `get_ram_usage`
- **THEN** the result contains `total_mb`, `available_mb`, `used_percent`, `low_memory`, and `threshold_mb`

---

### Requirement: Radio-toggling tools must be confirmation-gated
Any future tool that directly flips a radio state (WiFi, Bluetooth, mobile data, airplane mode) SHALL declare `requiresConfirmation = true` under the `action-confirmation` capability. The v1 settings deep-link tools SHALL NOT be gated, because the OS settings panel is the user's confirmation point.

#### Scenario: Future direct radio tool
- **WHEN** a new tool that directly enables/disables a radio is registered
- **THEN** it declares `requiresConfirmation = true` and a repeat-back describing the flip

---

### Requirement: Memory capture and history retrieval tools
The system SHALL implement two additional `LocalTool` implementations (registered set 17 total): `remember_fact(content)` — persists a durable user fact to the memory store, deduplicating identical trimmed text by refreshing its timestamp instead of duplicating; `search_chat_history(query)` — keyword-searches all stored conversations' user/assistant turns and returns up to 5 result snippets with conversation title and date. Neither tool SHALL require confirmation; both SHALL function offline.

#### Scenario: Model remembers a fact
- **WHEN** the user says "remember that my bike code is 4321" and the model invokes `remember_fact(content="Bike code is 4321")`
- **THEN** the fact is persisted with source `MODEL_TOOL`
- **AND** the model confirms to the user that it will remember

#### Scenario: Duplicate fact deduped
- **WHEN** `remember_fact` is invoked with text identical (trimmed) to an existing entry
- **THEN** no duplicate entry is created
- **AND** the existing entry's updated timestamp is refreshed

#### Scenario: New chat reaches prior history
- **WHEN** the user in a brand-new chat asks "what did I ask about the exam last week?" and the model invokes `search_chat_history(query="exam")`
- **THEN** matching turns from prior conversations are returned as snippets with conversation title and date
- **AND** the model can answer from those snippets

#### Scenario: Search finds nothing
- **WHEN** `search_chat_history` matches no stored turns
- **THEN** the tool returns an empty-results success
- **AND** the model tells the user it found nothing rather than inventing content
