## 1. Manifest

- [x] 1.1 Add permissions: `CAMERA`, `ACCESS_WIFI_STATE`, `BLUETOOTH_CONNECT` (API 31+), and legacy `BLUETOOTH` with `android:maxSdkVersion="30"`; add `<uses-feature android:name="android.hardware.camera.flash" android:required="false" />` (mirrors Gallery's manifest)

## 2. Tools

- [x] 2.1 `ToggleFlashlightTool` — probe `FLASH_INFO_AVAILABLE` across camera IDs, `setTorchMode(id, enabled)`, try/catch → `ToolResult.error` with the platform message; `requiredPermissions = listOf(CAMERA)` (on-demand flow)
- [x] 2.2 `OpenWifiSettingsTool` / `OpenBluetoothSettingsTool` — `startActivity(Intent(Settings.ACTION_...))` with `FLAG_ACTIVITY_NEW_TASK`, try/catch resolution failure → `ToolResult.error`; no permissions
- [x] 2.3 `GetWifiStateTool` — `WifiManager.isWifiEnabled`; `GetBluetoothStateTool` — `BluetoothManager.adapter.isEnabled`, returning `enabled="unknown"` + `reason="permission_not_granted"` when `BLUETOOTH_CONNECT` is missing (no prompt)
- [x] 2.4 `GetRamUsageTool` — `ActivityManager.MemoryInfo` → `total_mb`, `available_mb`, `used_percent`, `low_memory`, `threshold_mb`; no permission
- [x] 2.5 Unit tests per tool using the existing mocked-`ContextWrapper` robot pattern (torch probe paths, deep-link intent assertions, state degradation, RAM payload shape); confirm none of the six declares `requiresConfirmation`

## 3. Registration

- [x] 3.1 Register all six in `DefaultLocalTools.registerAll`; update `LocalToolsTest` count assertion 9 → 15

## 4. Validation

- [x] 4.1 `./gradlew test :app:assembleDebug` passes; all existing tool/session tests green
- [x] 4.2 Manual device matrix: "turn on/off flashlight" (torch physically toggles, CAMERA prompted on first use only), "open WiFi settings" / "open Bluetooth settings" (panels open), "is WiFi on?" / "is Bluetooth on?" (accurate, BT honest-unknown pre-grant), "how much RAM is free?" (plausible numbers); verify no confirmation card appears for any of the six; verify `call_contact` still gated
- [x] 4.3 `openspec validate device-control-tools` passes; tick all tasks