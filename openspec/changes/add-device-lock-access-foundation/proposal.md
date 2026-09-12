## Why

In assistant mode, particularly on the lock screen (e.g. invoked via Voice Interaction Services), an AI agent can perform certain safe utilities (alarms, timers, media, flashlight) but must be restricted from executing sensitive actions (opening third-party apps, exposing private communications, accessing settings) while the phone is locked. Currently, Loki has no awareness of device lock state and `ToolRegistry.executeDetailed()` lacks an execution access gate, allowing any tool called by the LLM to execute as long as Android runtime permissions pass.

Establishing a reliable, centralized security and capability foundation now prevents security holes, decouples domain tools from Android Keyguard APIs, and prepares Loki for a comprehensive lock-screen action matrix without prematurely hardcoding tool-by-tool restrictions.

## What Changes

- Introduce `DeviceLockState` enum (`LOCKED`, `UNLOCKED`) and pure `DeviceLockStateProvider` abstraction in `core:tools`.
- Introduce `ToolAccessPolicy` interface and `AccessDecision` (`Allow`, `Deny(reason, message)`) with `DenialReason` enum (`DEVICE_LOCKED`, `USER_NOT_AUTHORIZED`, `CAPABILITY_UNSUPPORTED`).
- Add `AndroidDeviceLockStateProvider` in `core:assistant` (wrapping `KeyguardManager.isKeyguardLocked`) and `FakeDeviceLockStateProvider` for testing.
- Introduce centralized access gating in `ToolRegistry.executeDetailed()`: evaluates `ToolAccessPolicy` against current `DeviceLockState` before checking permissions, validating arguments, or invoking `Tool.execute()`.
- Add `ToolExecutionResult.AccessDenied(val decision: AccessDecision.Deny)` to the execution result hierarchy.
- Add `ACCESS_DENIED` to `ToolErrorCode`.
- Update `ConversationSession` and `AssistantSession` to handle `AccessDenied` / `ACCESS_DENIED` cleanly by terminating the turn with clear lock-screen guidance instead of crashing or attempting background UI launches.
- Provide backward-compatible defaults in `ToolRegistry` (`DeviceLockState.UNLOCKED` provider and `AllowAllToolAccessPolicy`) so all existing test suites and tool registrations continue functioning seamlessly during Phase 1.

## Capabilities

### New Capabilities
- `device-lock-access-control`: Defines the device lock state abstraction, tool access policy contracts, and Android Keyguard state provider for access evaluation.

### Modified Capabilities
- `tool-registry`: Updates `ToolRegistry.executeDetailed()` to enforce access policy evaluation against current device lock state prior to permission verification or execution dispatch, returning `ToolExecutionResult.AccessDenied` when denied.

## Impact

- `core:tools`: New classes `DeviceLockState`, `DeviceLockStateProvider`, `ToolAccessPolicy`, `AccessDecision`, `DenialReason`. Modified `ToolRegistry` and `ToolResult.kt`.
- `core:assistant`: `AndroidDeviceLockStateProvider` implementation wrapping Android `KeyguardManager`. Updated `AssistantSession` to handle `ACCESS_DENIED`.
- `core:conversation`: `ConversationSession` updated to handle `ToolExecutionResult.AccessDenied`.
- `app`: `AppModule` updated to provide `AndroidDeviceLockStateProvider` and default `ToolAccessPolicy` when instantiating `ToolRegistry`.
