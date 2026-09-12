## 1. Domain Models & Contracts in core:tools

- [ ] 1.1 Create `DeviceLockState` enum (`LOCKED`, `UNLOCKED`) and `DeviceLockStateProvider` interface in `core:tools`
- [ ] 1.2 Create `DenialReason` enum, `AccessDecision` sealed interface (`Allow`, `Deny`), and `ToolAccessPolicy` interface in `core:tools`
- [ ] 1.3 Implement `AllowAllToolAccessPolicy` in `core:tools` as the permissive default policy
- [ ] 1.4 Update `ToolResult.kt` to add `ToolExecutionResult.AccessDenied` and `ToolErrorCode.ACCESS_DENIED`

## 2. Centralized Gate in ToolRegistry

- [ ] 2.1 Update `ToolRegistry` constructor to accept `DeviceLockStateProvider` and `ToolAccessPolicy` with backward-compatible defaults
- [ ] 2.2 Enforce access policy in `ToolRegistry.executeDetailed()` as the primary gate before permissions and argument validation
- [ ] 2.3 Update `ToolRegistry.execute()` to map `AccessDenied` to `ToolResult.error` with `ToolErrorCode.ACCESS_DENIED`
- [ ] 2.4 Add unit tests in `ToolRegistryTest.kt` verifying policy gate enforcement, denial short-circuiting, and default constructor compatibility

## 3. Platform Keyguard Provider & DI Wiring

- [ ] 3.1 Implement `AndroidDeviceLockStateProvider` in `core:assistant` using `KeyguardManager.isKeyguardLocked`
- [ ] 3.2 Update `AppModule.kt` to wire `AndroidDeviceLockStateProvider` and `AllowAllToolAccessPolicy` into `ToolRegistry`

## 4. Conversation & Assistant Session Rejection Handling

- [ ] 4.1 Update `ConversationSession.kt` to handle `ToolExecutionResult.AccessDenied` in the tool turn loop, logging the event and returning clean assistant turn text
- [ ] 4.2 Update `AssistantSession.kt` tool result observer to handle `ToolErrorCode.ACCESS_DENIED` cleanly without launching background settings intents
- [ ] 4.3 Add unit tests in `ConversationSessionTest.kt` and `AssistantSessionTest.kt` verifying access denial handling

## 5. Verification & Test Suite Integrity

- [ ] 5.1 Run `./gradlew :core:tools:test` to verify all tool tests pass
- [ ] 5.2 Run `./gradlew :core:conversation:test` and `./gradlew :core:assistant:test` to verify conversation and voice session suites pass with zero regressions
