## 1. Domain Models & Contracts in core:tools

- [x] 1.1 Create `DeviceLockState` enum (`LOCKED`, `UNLOCKED`) and `DeviceLockStateProvider` interface in `core:tools`
- [x] 1.2 Create `DenialReason` enum, `AccessDecision` sealed interface (`Allow`, `Deny`), and `ToolAccessPolicy` interface (`evaluate(tool, arguments, lockState)`) in `core:tools`
- [x] 1.3 Implement `AllowAllToolAccessPolicy` and `FakeDeviceLockStateProvider` in `core:tools`
- [x] 1.4 Update `ToolResult.kt` to add `ToolExecutionResult.AccessDenied` and `ToolErrorCode.ACCESS_DENIED`

## 2. Centralized Gate in ToolRegistry

- [x] 2.1 Update `ToolRegistry` constructor to accept `DeviceLockStateProvider` and `ToolAccessPolicy` with backward-compatible defaults
- [x] 2.2 Enforce access policy in `ToolRegistry.executeDetailed()` after tool lookup and before permissions or argument validation
- [x] 2.3 Update `ToolRegistry.execute()` to map `AccessDenied` to `ToolResult.error` with `ToolErrorCode.ACCESS_DENIED`
- [x] 2.4 Add unit tests in `ToolRegistryTest.kt` verifying policy gate enforcement, denial short-circuiting, parameter propagation, and default constructor compatibility

## 3. Platform Keyguard Provider & DI Wiring

- [x] 3.1 Implement `AndroidDeviceLockStateProvider` in `core:tools` using `KeyguardManager.isKeyguardLocked` with fail-closed null fallback
- [x] 3.2 Update `AppModule.kt` to wire `AndroidDeviceLockStateProvider` and `AllowAllToolAccessPolicy` into `ToolRegistry`

## 4. Conversation & Assistant Session Rejection Handling

- [x] 4.1 Update `ConversationSession.kt` to handle `ToolExecutionResult.AccessDenied` with immediate loop break, deterministic guidance, and defensive pre-lookup handling
- [x] 4.2 Update `AssistantSession.kt` tool result observer to handle `ToolErrorCode.ACCESS_DENIED` cleanly by speaking guidance and completing turn without re-arming mic or launching settings
- [x] 4.3 Add unit tests in `ConversationSessionTest.kt` and `AssistantSessionTest.kt` with a denying policy to verify rejection handling, deterministic copy, and session completion

## 5. Verification & Test Suite Integrity

- [x] 5.1 Run `./gradlew :core:tools:test` to verify all tool tests pass
- [x] 5.2 Run `./gradlew :core:conversation:test` and `./gradlew :core:assistant:test` to verify conversation and voice session suites pass with zero regressions
