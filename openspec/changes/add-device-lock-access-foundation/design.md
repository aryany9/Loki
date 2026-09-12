## Context

Loki runs as an Android voice and chat assistant. When invoked via Android Voice Interaction Services (e.g. from the lock screen / keyguard), the assistant overlay is displayed over the lock screen using window flags (`FLAG_SHOW_WHEN_LOCKED`). However, Loki currently has no mechanism to determine whether the device is locked, nor does it enforce any access policy during tool execution.

In `ToolRegistry.executeDetailed()`, tools are executed whenever Android runtime permissions are satisfied. If an on-device LLM emits a tool call like `open_app(packageName="com.google.android.youtube")` or `open_wifi_settings` while the device is locked, the underlying Android intent is launched behind the keyguard without user visibility or feedback, leaving the user confused on the lock screen. Furthermore, sensitive operations (contacts, messaging, memory facts) have no mechanism to declare lock-screen safety.

To build a reliable Gemini alternative, Loki requires a clear separation between:
1. **Foundation (Phase 1)**: Lock state detection, access policy abstractions, and a single centralized enforcement gate.
2. **Action Matrix (Phase 2)**: The specific tool-by-tool classification (always allowed, personal results opt-in, strict unlock required).

## Goals / Non-Goals

**Goals:**
- Provide a pure Kotlin `DeviceLockState` enum (`LOCKED`, `UNLOCKED`) and `DeviceLockStateProvider` interface in `core:tools`.
- Provide an Android platform implementation `AndroidDeviceLockStateProvider` in `core:assistant` that queries `KeyguardManager.isKeyguardLocked`.
- Define `ToolAccessPolicy` and `AccessDecision` (`Allow`, `Deny(DenialReason, message)`) with `DenialReason` enum (`DEVICE_LOCKED`, `USER_NOT_AUTHORIZED`, `CAPABILITY_UNSUPPORTED`).
- Establish a single, centralized access gate in `ToolRegistry.executeDetailed()` evaluated before permissions or argument validation.
- Introduce `ToolExecutionResult.AccessDenied` and `ToolErrorCode.ACCESS_DENIED`.
- Handle `AccessDenied` cleanly in `ConversationSession` and `AssistantSession` without crashes or launching trapped background activities.
- Ensure 100% backward compatibility with all existing unit tests in `core:tools` and `core:conversation`.

**Non-Goals:**
- Defining which specific tools are locked vs unlocked (Phase 2 Action Matrix).
- Building the "Personal Results on Lock Screen" toggle or DataStore persistence (Phase 2).
- Implementing keyguard dismissal challenge flow (`KeyguardManager.requestDismissKeyguard`) or automatic resumption of pending tool calls upon unlock (Phase 2).
- Filtering the grammar or hiding tools from the LLM prompt during lock screen mode.

## Decisions

### Decision 1: Naming - `ToolAccessPolicy` over `ToolExecutionPolicy`
- **Choice:** Name the policy interface `ToolAccessPolicy` and decision model `AccessDecision`.
- **Rationale:** "Execution policy" implies concurrency, thread pools, timeouts, or retry logic. What we are implementing is access control: *"Is access to this tool granted under current device conditions?"*
- **Alternative Considered:** `ToolExecutionPolicy` (too broad, conflates execution mechanics with authorization) or `ToolLockScreenPolicy` (too narrow if future policies evaluate other conditions).

### Decision 2: Constructor Injection with Backward-Compatible Defaults in `ToolRegistry`
- **Choice:** Inject `DeviceLockStateProvider` and `ToolAccessPolicy` into `ToolRegistry`'s constructor with default fallback values:
  ```kotlin
  class ToolRegistry(
      private val lockStateProvider: DeviceLockStateProvider = DeviceLockStateProvider { DeviceLockState.UNLOCKED },
      private val accessPolicy: ToolAccessPolicy = AllowAllToolAccessPolicy
  )
  ```
- **Rationale:** Over 100 existing unit tests construct `ToolRegistry()` with no arguments. Defaulting to an unlocked state and an `AllowAllToolAccessPolicy` prevents breaking existing tests while allowing explicit fakes (`FakeDeviceLockStateProvider`, test policies) in new test suites. Production wiring in `AppModule` supplies the real `AndroidDeviceLockStateProvider`.
- **Alternative Considered:** Passing `DeviceLockStateProvider` into every `executeDetailed` call. This would pollute callers (`ConversationSession`) with infrastructure dependencies that belong in the registry/service container.

### Decision 3: Execution-Only Gating (Preserving Model Awareness in Grammar)
- **Choice:** Evaluate `ToolAccessPolicy` exclusively at execution time within `ToolRegistry.executeDetailed()`. Keep `ToolRegistry.getAvailableTools()` grammar generation unchanged in Phase 1.
- **Rationale:** In Gemini and Google Assistant, if a user on the lock screen says *"Open YouTube"*, the assistant recognizes the intent and specifically replies *"You'll need to unlock your phone to open YouTube"*. If restricted tools were stripped from the grammar, the model would hallucinate or respond *"I don't know how to open apps"*. Enforcing at the execution gate ensures the agent understands the user's intent while strictly preventing unauthorized execution.
- **Alternative Considered:** Filtering tools from grammar during lock screen. This degraded user experience by producing confusing, unhelpful responses.

### Decision 4: Independence from Verbal Confirmation State Machine
- **Choice:** `ToolAccessPolicy` operates completely independently from `ConfirmationResolver` and verbal confirmation gates.
- **Rationale:** Access control is a precondition for execution. If a tool is denied by `ToolAccessPolicy`, execution halts immediately. The user is never asked for verbal confirmation (e.g. *"Shall I call Mom?"*) for an action that is not permitted to run.

## Risks / Trade-offs

- **[Risk] Multiple lock APIs on Android (`isKeyguardLocked` vs `isDeviceLocked`)**
  → *Mitigation*: Use `keyguardManager.isKeyguardLocked`. Even if a device has no secure PIN set (swipe to unlock), an active keyguard will hide activities launched by background tools. Checking `isKeyguardLocked` accurately reflects whether the keyguard is currently obscuring the display.
- **[Risk] Direct Boot (FBE) state before first unlock**
  → *Mitigation*: Phase 1 requires that Loki is invoked while the OS is booted. Future storage migrations to Device Protected Storage will address Direct Boot explicitly.
- **[Risk] Pre-call contact lookup in `ConversationSession`**
  → *Mitigation*: In `ConversationSession`, internal pre-lookups call `toolRegistry.executeDetailed()`. If denied, it cleanly halts with `AccessDenied` feedback rather than failing silently.
