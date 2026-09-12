## Purpose
Platform-agnostic device lock state detection and pluggable tool access policy evaluation.

## Requirements

### Requirement: Device lock state abstraction
The system SHALL provide a platform-agnostic `DeviceLockState` enum with values `LOCKED` and `UNLOCKED`, and a `DeviceLockStateProvider` interface with a `fun currentState(): DeviceLockState` method.

#### Scenario: Provider reports locked state
- **WHEN** the device keyguard is engaged
- **THEN** `DeviceLockStateProvider.currentState()` returns `DeviceLockState.LOCKED`

#### Scenario: Provider reports unlocked state
- **WHEN** the device keyguard is dismissed or not active
- **THEN** `DeviceLockStateProvider.currentState()` returns `DeviceLockState.UNLOCKED`

---

### Requirement: Android Keyguard state provider
The system SHALL provide an `AndroidDeviceLockStateProvider` implementation in `core:tools` that inspects the Android `KeyguardManager.isKeyguardLocked` property using the application context. If `KeyguardManager` is unavailable (null), it SHALL fail closed by reporting `DeviceLockState.LOCKED`.

#### Scenario: Keyguard is showing
- **WHEN** Android `KeyguardManager.isKeyguardLocked` returns `true`
- **THEN** `AndroidDeviceLockStateProvider.currentState()` returns `DeviceLockState.LOCKED`

#### Scenario: Keyguard is dismissed
- **WHEN** Android `KeyguardManager.isKeyguardLocked` returns `false`
- **THEN** `AndroidDeviceLockStateProvider.currentState()` returns `DeviceLockState.UNLOCKED`

#### Scenario: KeyguardManager is unavailable
- **WHEN** `context.getSystemService(Context.KEYGUARD_SERVICE)` returns `null`
- **THEN** `AndroidDeviceLockStateProvider.currentState()` returns `DeviceLockState.LOCKED`

---

### Requirement: Tool access policy interface and decision model
The system SHALL define a `ToolAccessPolicy` interface with `fun evaluate(tool: Tool, arguments: Map<String, Any?> = emptyMap(), deviceLockState: DeviceLockState): AccessDecision`. The `AccessDecision` SHALL be a sealed interface with `AccessDecision.Allow` and `AccessDecision.Deny(val reason: DenialReason, val message: String? = null)`. The `DenialReason` enum SHALL support at minimum `DEVICE_LOCKED`, `USER_NOT_AUTHORIZED`, and `CAPABILITY_UNSUPPORTED`.

#### Scenario: Policy allows access
- **WHEN** a tool execution request satisfies access policy criteria
- **THEN** `ToolAccessPolicy.evaluate()` returns `AccessDecision.Allow`

#### Scenario: Policy denies access due to device lock
- **WHEN** a tool requires unlocked device state but `deviceLockState` is `LOCKED`
- **THEN** `ToolAccessPolicy.evaluate()` returns `AccessDecision.Deny(reason = DenialReason.DEVICE_LOCKED)`

---

### Requirement: Permissive default policy for Phase 1
The system SHALL provide an `AllowAllToolAccessPolicy` implementing `ToolAccessPolicy` that unconditionally returns `AccessDecision.Allow` for all tools, arguments, and lock states, serving as the default implementation during foundation rollout.

#### Scenario: Allow all policy evaluates any tool
- **WHEN** `AllowAllToolAccessPolicy.evaluate(tool, arguments, lockState)` is called with any tool, arguments, and any `DeviceLockState`
- **THEN** it returns `AccessDecision.Allow`

---

### Requirement: Lock screen action matrix policy
The system SHALL provide a `LockScreenActionMatrixPolicy` implementing `ToolAccessPolicy` that enforces tool access based on device lock state:
1. When `deviceLockState` is `DeviceLockState.UNLOCKED`, it SHALL unconditionally return `AccessDecision.Allow`.
2. When `deviceLockState` is `DeviceLockState.LOCKED`, it SHALL permit execution ONLY for tools explicitly present in its lock-screen allowlist, returning `AccessDecision.Allow`.
3. When `deviceLockState` is `DeviceLockState.LOCKED` and the tool is NOT in the allowlist, it SHALL return `AccessDecision.Deny(reason = DenialReason.DEVICE_LOCKED, message = <contextual message>)`.

#### Scenario: Tool execution allowed when device is unlocked
- **WHEN** `LockScreenActionMatrixPolicy.evaluate(tool, arguments, DeviceLockState.UNLOCKED)` is called for any tool
- **THEN** it returns `AccessDecision.Allow`

#### Scenario: Allowed tool executed when device is locked
- **WHEN** `LockScreenActionMatrixPolicy.evaluate(tool, arguments, DeviceLockState.LOCKED)` is called
- **AND** `tool.name` is present in the lock-screen allowlist (e.g. `call_contact`, `toggle_flashlight`, `get_current_time`)
- **THEN** it returns `AccessDecision.Allow`

#### Scenario: Restricted tool denied when device is locked
- **WHEN** `LockScreenActionMatrixPolicy.evaluate(tool, arguments, DeviceLockState.LOCKED)` is called
- **AND** `tool.name` is not in the lock-screen allowlist (e.g. `open_app`, `search_chat_history`, `open_wifi_settings`)
- **THEN** it returns `AccessDecision.Deny` with `reason = DenialReason.DEVICE_LOCKED`

---

### Requirement: Default lock-screen tool classification
`LockScreenActionMatrixPolicy` SHALL define default allowlisted tools that include:
- Phone & calling: `call_contact`, `dial_number`, `lookup_contact`, `select_contact`
- Media & hardware toggles: `media_control`, `toggle_flashlight`
- Timers & alarms: `set_timer`, `set_alarm`
- Public device status: `get_current_time`, `get_battery_status`, `get_wifi_state`, `get_bluetooth_state`, `get_ram_usage`
- Conversational confirmation: `ask_user`
All other tools, including `open_app`, `open_wifi_settings`, `open_bluetooth_settings`, `search_chat_history`, and `remember_fact`, SHALL be excluded from the default lock-screen allowlist.

#### Scenario: Phone call tool is permitted on lock screen
- **WHEN** `deviceLockState` is `LOCKED`
- **AND** the tool is `call_contact` or `dial_number`
- **THEN** `LockScreenActionMatrixPolicy` returns `AccessDecision.Allow`

#### Scenario: App launch tool is restricted on lock screen
- **WHEN** `deviceLockState` is `LOCKED`
- **AND** the tool is `open_app`
- **THEN** `LockScreenActionMatrixPolicy` returns `AccessDecision.Deny`

#### Scenario: Chat history search is restricted on lock screen
- **WHEN** `deviceLockState` is `LOCKED`
- **AND** the tool is `search_chat_history`
- **THEN** `LockScreenActionMatrixPolicy` returns `AccessDecision.Deny`

---

### Requirement: Contextual lock-screen denial guidance
When denying tool execution due to `DenialReason.DEVICE_LOCKED`, `LockScreenActionMatrixPolicy` SHALL produce human-friendly guidance:
- For `open_app`: "Please unlock your phone to open <app_name>." (or "Please unlock your phone to open apps." if `app_name` is unspecified).
- For settings tools (`open_wifi_settings`, `open_bluetooth_settings`): "Please unlock your device to change settings."
- For memory/history tools (`search_chat_history`, `remember_fact`): "Please unlock your device to access personal memory and history."
- For other restricted tools: "Please unlock your device to use this feature."

#### Scenario: Open app denial specifies target app name
- **WHEN** `open_app` with `arguments = {"app_name": "YouTube"}` is evaluated while `LOCKED`
- **THEN** the returned `AccessDecision.Deny.message` contains "Please unlock your phone to open YouTube."

#### Scenario: Generic restricted tool denial produces fallback guidance
- **WHEN** an unlisted tool without custom message mapping is evaluated while `LOCKED`
- **THEN** the returned `AccessDecision.Deny.message` contains "Please unlock your device to use this feature."

---

### Requirement: Production dependency injection of LockScreenActionMatrixPolicy
The application dependency injection module (`AppModule`) SHALL provide `LockScreenActionMatrixPolicy` as the `ToolAccessPolicy` instance injected into `ToolRegistry`.

#### Scenario: Production ToolRegistry uses LockScreenActionMatrixPolicy
- **WHEN** the application initializes `ToolRegistry` via dependency injection
- **THEN** `ToolRegistry` evaluates access decisions using `LockScreenActionMatrixPolicy`

