## 1. Core Policy Implementation

- [x] 1.1 Implement `LockScreenActionMatrixPolicy` in `core:tools` with allowlist for lock screen and contextual denial messages
- [x] 1.2 Create `LockScreenActionMatrixPolicyTest` in `core:tools` covering unlocked bypass, allowlisted tools, and restricted tools with contextual messages

## 2. ToolRegistry Integration Tests

- [x] 2.1 Add integration test in `ToolRegistryTest` confirming `LockScreenActionMatrixPolicy` blocks `open_app` and permits `call_contact` when `LOCKED`

## 3. Dependency Injection & Wiring

- [x] 3.1 Update `AppModule` in `app` to bind `LockScreenActionMatrixPolicy` as the production `ToolAccessPolicy`

## 4. Verification & Validation

- [x] 4.1 Execute test suites (`:core:tools:test`, `:core:conversation:test`, `:core:assistant:test`)
- [x] 4.2 Build debug APK and verify compilation
