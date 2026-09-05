# local-android-tools — Delta

## ADDED Requirements

### Requirement: Device-control tools implemented
The system SHALL implement the following six device-control tools as `LocalTool` implementations registered in `ToolRegistry` (extending the registered set from nine to fifteen): `toggle_flashlight`, `open_wifi_settings`, `open_bluetooth_settings`, `get_wifi_state`, `get_bluetooth_state`, `get_ram_usage`. All SHALL function without internet connectivity.

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

### Requirement: Connectivity state queries are read-only and never prompt
`get_wifi_state` and `get_bluetooth_state` SHALL report current radio state without requesting permissions or side effects. When the Bluetooth state cannot be read due to a missing `BLUETOOTH_CONNECT` grant (API 31+), the tool SHALL return an `enabled="unknown"` result with a reason instead of triggering a permission request.

#### Scenario: WiFi state answered
- **WHEN** the user asks whether WiFi is on and the model invokes `get_wifi_state`
- **THEN** the result contains the current enabled state with no permission interaction

#### Scenario: Bluetooth state without permission
- **WHEN** `get_bluetooth_state` runs without `BLUETOOTH_CONNECT` granted on API 31+
- **THEN** the result reports `enabled="unknown"` with the reason `permission_not_granted`
- **AND** no permission dialog is raised

### Requirement: RAM usage reported from system memory info
`get_ram_usage` SHALL return total memory, available memory, used percentage, the low-memory flag, and the system low-memory threshold from `ActivityManager.MemoryInfo`, requiring no permission and excluding the assistant's own process heap.

#### Scenario: RAM question answered
- **WHEN** the model invokes `get_ram_usage`
- **THEN** the result contains `total_mb`, `available_mb`, `used_percent`, `low_memory`, and `threshold_mb`

### Requirement: Radio-toggling tools must be confirmation-gated
Any future tool that directly flips a radio state (WiFi, Bluetooth, mobile data, airplane mode) SHALL declare `requiresConfirmation = true` under the `action-confirmation` capability. The v1 settings deep-link tools SHALL NOT be gated, because the OS settings panel is the user's confirmation point.

#### Scenario: Future direct radio tool
- **WHEN** a new tool that directly enables/disables a radio is registered
- **THEN** it declares `requiresConfirmation = true` and a repeat-back describing the flip