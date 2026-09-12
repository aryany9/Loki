## Context

Loki runs as an Android voice and chat assistant. When invoked via Android Voice Interaction Services (e.g. from the lock screen / keyguard), the assistant overlay is displayed over the lock screen using window flags (`FLAG_SHOW_WHEN_LOCKED`). However, Loki currently has no mechanism to determine whether the device is locked, nor does it enforce any access policy during tool execution.

In `ToolRegistry.executeDetailed()`, tools are executed whenever Android runtime permissions are satisfied. If an on-device LLM emits a tool call like `open_app(packageName="com.google.android.youtube")` or `open_wifi_settings` while the device is locked, the underlying Android intent is launched behind the keyguard without user visibility or feedback, leaving the user confused on the lock screen. Furthermore, sensitive operations (contacts, messaging, memory facts) have no mechanism to declare lock-screen safety.

To build a reliable Gemini alternative, Loki requires a clear separation between:
1. **Foundation (Phase 1)**: Lock state detection, access policy abstractions, and a single centralized enforcement gate.
2. **Action Matrix (Phase 2)**: The specific tool-by-tool classification (always allowed, personal results opt-in, strict unlock required).

## Goals / Non-Goals

**Goals:**
- Provide a pure Kotlin `DeviceLockState` enum (`LOCKED`, `UNLOCKED`) and `DeviceLockStateProvider` interface in `core:tools`.
- Provide an Android platform implementation `AndroidDeviceLockStateProvider` in `core:tools` that queries `KeyguardManager.isKeyguardLocked` with fail-closed fallback.
- Define `ToolAccessPolicy` (accepting `tool: Tool`, `arguments: Map<String, Any?> = emptyMap()`, and `deviceLockState: DeviceLockState`) and `AccessDecision` (`Allow`, `Deny(DenialReason, message)`) with `DenialReason` enum (`DEVICE_LOCKED`, `USER_NOT_AUTHORIZED`, `CAPABILITY_UNSUPPORTED`).
- Establish a single, centralized access gate in `ToolRegistry.executeDetailed()` evaluated after tool lookup but before permissions or argument validation.
- Introduce `ToolExecutionResult.AccessDenied` and `ToolErrorCode.ACCESS_DENIED`.
- Handle `AccessDenied` cleanly in `ConversationSession` (immediate loop break with deterministic guidance, defensive pre-lookup handling) and `AssistantSession` (clean turn completion without re-arming mic or launching settings).
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

### Decision 4: Independence from Verbal Confirmation State Machine & Calling as Lock-Screen Permitted Utility
- **Choice:** `ToolAccessPolicy` operates as a precondition before execution and verbal confirmation. However, communication utilities (`lookup_contact`, `call_contact`, `hang_up`) are classified as lock-screen compatible utilities (aligning with Gemini and Android Assistant standards).
- **Rationale:** Making phone calls hands-free (while driving or with a locked phone in pocket) is a primary assistant utility. Android's Telecom framework (`TelecomManager` / `Intent.ACTION_CALL`) natively supports display over the keyguard (`FLAG_SHOW_WHEN_LOCKED`). Safety against inadvertent dialing is guaranteed by Loki's existing verbal confirmation gate (*"Shall I call Alice?"*), rather than locking the user out.

### Decision 5: Policy Signature Includes Arguments for Granular Gating
- **Choice:** `ToolAccessPolicy.evaluate` accepts `(tool: Tool, arguments: Map<String, Any?> = emptyMap(), deviceLockState: DeviceLockState)`.
- **Rationale:** Mobile access control often depends on arguments (e.g. emergency numbers vs personal contacts, camera vs banking package name in `open_app`). Since `executeDetailed` already receives arguments, including them in the contract now prevents breaking signature changes in Phase 2.

### Decision 6: Deterministic Turn Termination on Access Denied
- **Choice:** When `executeDetailed` returns `AccessDenied`, `ConversationSession` immediately halts the ReAct loop (`break`) and outputs deterministic guidance rather than re-prompting the LLM.
- **Rationale:** Re-prompting an on-device SLM on the lock screen introduces 1.5–3 seconds of latency, drains battery, and risks the model hallucinating or trying alternative forbidden tools. Immediate termination provides instant, predictable security feedback.

### Decision 7: Placement of `AndroidDeviceLockStateProvider` in `core:tools`
- **Choice:** Place `AndroidDeviceLockStateProvider` in `core:tools` alongside `DeviceLockStateProvider` and `PermissionManager`.
- **Rationale:** `core:tools` is already an Android library module housing `PermissionManager`. `KeyguardManager` is an Android framework service, not an assistant-specific concept. Housing the platform provider in `core:tools` keeps the module cohesive and prevents circular or awkward dependencies from `app` or test modules.

## Risks / Trade-offs

- **[Risk] Multiple lock APIs on Android (`isKeyguardLocked` vs `isDeviceLocked`)**
  → *Mitigation*: Use `keyguardManager.isKeyguardLocked`. Even if a device has no secure PIN set (swipe to unlock), an active keyguard will hide activities launched by background tools. Checking `isKeyguardLocked` accurately reflects whether the keyguard is currently obscuring the display. If `KeyguardManager` is null (e.g. headless tests), fail closed to `DeviceLockState.LOCKED`.
- **[Risk] Direct Boot (FBE) state before first unlock**
  → *Mitigation*: Phase 1 requires that Loki is invoked while the OS is booted. Future storage migrations to Device Protected Storage will address Direct Boot explicitly.
- **[Risk] Defensive pre-call contact lookup in `ConversationSession`**
  → *Mitigation*: While `lookup_contact` is permitted under normal lock-screen policy, `ConversationSession` defensively checks if `lookupExec is ToolExecutionResult.AccessDenied` and cleanly terminates the turn rather than falling into an invalid or unconfirmed calling state.
