# Design: device-control-tools

## Context

Loki's tool system (`LocalTool` + `ToolRegistry` + `ToolResult`, permission-gated on demand via `PermissionManager`) currently registers 9 tools (`DefaultLocalTools.kt`). Google's AI Edge Gallery — the reference implementation the user directed us to — demonstrates the "Mobile Actions" category with a two-tier pattern (verified in `customtasks/mobileactions/`): tools *propose* via `onFunctionCalled(Action)`, and the ViewModel *executes* — `setFlashlight()` uses `CameraManager.setTorchMode` after probing `FLASH_INFO_AVAILABLE` (lines 268-301), while `openWifiSettings()` is a plain `Intent(Settings.ACTION_WIFI_SETTINGS)` deep link (lines 374-384). Bluetooth is absent from Gallery's action set entirely.

Android constraint recap for our `minSdk=29` fleet: `WifiManager.setWifiEnabled` is deprecated and a no-op for third-party apps on 29+; `BluetoothAdapter.enable()/disable()` is deprecated (33+) and throws `SecurityException` for third-party callers. Direct radio toggling is not a viable cross-device contract.

## Goals / Non-Goals

- **Goals**: assistant can control the flashlight, hand off WiFi/Bluetooth changes to the OS panel, answer connectivity-state questions, and report RAM — all offline, all through the existing tool pipeline.
- **Non-Goals**: programmatic WiFi/BT toggling (platform-blocked; revisit only if a future Android exposes a sanctioned API); hotspot/DND/airplane-mode tools; app-lifecycle torch management (auto-off on exit); confirmation gates on these v1 tools.

## Decisions

### D1: Two-tier execution, per Gallery
- **Direct where permitted**: `toggle_flashlight` runs `CameraManager.setTorchMode(cameraId, enabled)`, probing `getCameraCharacteristics(...).FLASH_INFO_AVAILABLE` for the flash-capable camera ID (Gallery lines 268-301 verbatim pattern), with try/catch returning the error string in a `ToolResult.error`.
- **Deep link where blocked**: `open_wifi_settings` / `open_bluetooth_settings` fire `Settings.ACTION_*` intents with `FLAG_ACTIVITY_NEW_TASK`. The tool returns success when the intent resolves and a `ToolResult.error` (with the platform message) when the panel is unavailable — the model can then say so.
*Alternative rejected:* attempting `setWifiEnabled`/`enable()` with deep-link fallback — the direct path silently no-ops or throws depending on OEM/OS version, producing unpredictable "success" reports to the model. Gallery deliberately omits direct toggles; we match that.

### D2: Read-only state queries degrade gracefully
`get_wifi_state` uses `WifiManager.isWifiEnabled` (needs `ACCESS_WIFI_STATE`). `get_bluetooth_state` uses `BluetoothManager.adapter.isEnabled` — on API 31+ this requires `BLUETOOTH_CONNECT`; if the permission is not granted the tool returns `ToolResult.success(mapOf("enabled" to "unknown", "reason" to "permission_not_granted"))` instead of erroring, so the model answers honestly rather than triggering a permission loop for a read-only question. *Rationale:* state questions must never side-effect into permission prompts; only the settings deep links (which need nothing) and torch (CAMERA, on-demand) may prompt.

### D3: RAM reporting from ActivityManager.MemoryInfo
`get_ram_usage` returns `total_mb`, `available_mb`, `used_percent`, `low_memory` ("true"/"false"), and `threshold_mb` — no permission required. App-internal heap (`Debug.getNativeHeapSize`) is deliberately excluded: the user asked about *device* RAM, and the LLM's own allocation would pollute the number.

### D4: No confirmation gates on v1 device tools
Torch toggling is instantly reversible and user-initiated; the settings deep links leave the actual radio flip to the user inside the OS panel — **the panel itself is the confirmation point**, so a gate would double-prompt for no safety gain. Consequence (spec-ized): any future tool that directly flips a radio state MUST declare `requiresConfirmation = true` under the `action-confirmation` capability. *This refines the earlier plan* ("radios ride the gate") — the gate becomes unnecessary precisely because we chose the deep-link pattern that makes the OS the confirming party.

### D5: Tool naming and results follow existing conventions
Snake-case names (`toggle_flashlight`, `open_wifi_settings`, `get_wifi_state`, `get_bluetooth_state`, `get_ram_usage`), `Map<String, String>` data payloads, `ToolResult.success/error` — identical shape to `get_battery_status` so prompt-side tool signatures and the `ToolResultCard` render without any conversation-layer changes.

## Risks / Trade-offs

- **Torch reclamation by other apps**: only one app may hold torch mode; if the user's camera app takes it, our toggle-off later still succeeds (`setTorchMode(false)` is idempotent per camera). No app-side state is tracked in v1 — torch persists until toggled off, matching Gallery (which force-clears only on VM reset; a Loki voice session may leave torch on, acceptable and surfaced in manual checks).
- **OEM settings-panel differences**: some OEMs rename/redirect settings panels; deep links can fail to resolve → handled by intent-resolution try/catch feeding `ToolResult.error` so the model reports honestly.
- **`BLUETOOTH_CONNECT` runtime prompt**: never triggered by `get_bluetooth_state` (D2); only triggered if a future write-capable BT tool lands.

## Migration Plan

Purely additive: 6 new tool classes, registration list extension, manifest additions. No changes to existing tools, session, or UI. `LocalToolsTest` count assertion updates 9 → 15.

## Open Questions

- None.