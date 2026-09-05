# Proposal: device-control-tools

## Why

Loki can call contacts and set alarms but cannot control the device it runs on — users asking "turn on the flashlight", "is WiFi on?", or "how much RAM is free?" get nothing. Google's AI Edge Gallery implements exactly this category ("Mobile Actions") with a proven two-tier pattern: **direct execution where Android permits** (flashlight via `CameraManager.setTorchMode`), and **system-settings deep links where it does not** (WiFi/Bluetooth toggles are blocked for third-party apps on our `minSdk=29`+ fleet — Google themselves deep-link). Loki should adopt that pattern natively in its existing `LocalTool` architecture.

## What Changes

- Add **six new local tools**, registered in `DefaultLocalTools` (tool count 9 → 15):
  - `toggle_flashlight` — direct torch control via `CameraManager.setTorchMode`, probing `FLASH_INFO_AVAILABLE` (Gallery pattern)
  - `open_wifi_settings` / `open_bluetooth_settings` — deep link to the OS settings panel (`Settings.ACTION_WIFI_SETTINGS` / `ACTION_BLUETOOTH_SETTINGS`); the user completes the toggle there
  - `get_wifi_state` / `get_bluetooth_state` — read-only state queries so the assistant answers "is WiFi on?"
  - `get_ram_usage` — read-only system memory via `ActivityManager.MemoryInfo` (total / available / used % / low-memory flag)
- Manifest additions: `CAMERA` permission (on-demand requested by the existing tool permission flow), `ACCESS_WIFI_STATE`, `BLUETOOTH_CONNECT` (API 31+) + legacy `BLUETOOTH` (maxSdk 30), and `android.hardware.camera.flash` uses-feature (`required=false`) — mirroring Gallery's manifest.
- **No confirmation gate on these v1 tools**: torch is trivially reversible, and the settings deep-link leaves the destructive step to the user inside the OS panel. Any future tool that *directly* flips a radio MUST declare `requiresConfirmation` per the `action-confirmation` capability.

## Capabilities

### New Capabilities

### Modified Capabilities
- `local-android-tools`: adds the six device-control tools as a new requirement (direct torch execution, settings deep-link pattern for radios, read-only state/RAM queries, on-demand permissions), extending the registered tool set from nine to fifteen.

## Impact

- **`core/tools/local`**: 6 new `LocalTool` implementations; `DefaultLocalTools.registerAll` extended.
- **`app` manifest**: 3 permissions + 1 uses-feature (no runtime prompt until a tool triggers the existing on-demand flow).
- **No new dependencies, no conversation/session changes, no UI changes** — tools surface through the existing `ToolResultCard` flow and voice pipeline unchanged.
- Tests: `LocalToolsTest` tool count 9 → 15 plus per-tool unit tests with mocked system services (existing `ContextWrapper` robot pattern).