## ADDED Requirements

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
