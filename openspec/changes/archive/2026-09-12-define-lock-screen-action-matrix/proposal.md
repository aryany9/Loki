## Why

In Phase 1 (`add-device-lock-access-foundation`), we established the core infrastructure for detecting device lock states (`DeviceLockStateProvider`) and evaluating tool execution permissions (`ToolAccessPolicy`). However, Phase 1 shipped with `AllowAllToolAccessPolicy` to maintain full backward compatibility while verifying device-level audio and package resolution.

Running permissive tool access on the lock screen exposes sensitive user data (e.g., chat history) and allows launching interactive apps (e.g., YouTube or settings) over the keyguard. Phase 2 defines and implements the production lock-screen action matrix policy to securely partition which tools are permitted while locked (e.g., calling, media control, flashlight, alarms/timers, device status) versus tools requiring an unlocked device (e.g., launching apps, accessing chat history, opening settings).

## What Changes

- Implement `MatrixToolAccessPolicy` (or `LockScreenActionMatrixPolicy`) implementing `ToolAccessPolicy` that enforces a declarative lock-screen permission matrix.
- Classify all built-in local tools into lock-screen accessibility tiers:
  - **Permitted on Lock Screen**: Phone calls & dialing (`call_contact`, `dial_number`, `lookup_contact`, `select_contact`), device toggles & media (`toggle_flashlight`, `media_control`), timers & alarms (`set_timer`, `set_alarm`), public device status (`get_current_time`, `get_battery_status`, `get_wifi_state`, `get_bluetooth_state`, `get_ram_usage`), and internal interaction (`ask_user`).
  - **Restricted on Lock Screen (Require Unlock)**: App launching (`open_app`), system settings navigation (`open_wifi_settings`, `open_bluetooth_settings`), and private conversational memory (`search_chat_history`, `remember_fact`).
- Provide human-friendly, context-specific denial guidance messages when actions are blocked on the lock screen (e.g., "Please unlock your phone to open YouTube", "Unlock your device to access chat history").
- Wire the matrix policy as the default production `ToolAccessPolicy` in `AppModule`.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `device-lock-access-control`: Introduce the declarative lock-screen action matrix policy, tool classification rules, and contextual lock-screen denial guidance to replace the permissive default policy.

## Impact

- `core:tools`: Adds `LockScreenActionMatrixPolicy` and policy configuration models.
- `app`: Updates `AppModule` to bind `LockScreenActionMatrixPolicy` instead of `AllowAllToolAccessPolicy`.
- User Experience: On the lock screen, requesting restricted actions (like opening YouTube or searching chat history) will cleanly notify the user to unlock their device instead of attempting to launch over the keyguard. Safe actions like calling contacts remain completely functional on the lock screen.
