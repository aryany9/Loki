## Context

In Phase 1, we introduced `ToolAccessPolicy` and `DeviceLockStateProvider`, but deployed `AllowAllToolAccessPolicy` in production so we could verify end-to-end voice intent execution and Android 11+ package visibility without interference.

Testing with a locked physical device confirmed that `AllowAllToolAccessPolicy` allowed the assistant to execute `OpenAppTool` while the keyguard was up, causing YouTube to launch over the lockscreen. Furthermore, sensitive user history (`search_chat_history`, `remember_fact`) and settings activities could be invoked without authentication.

In Phase 2, we introduce `LockScreenActionMatrixPolicy` as the authoritative lock-screen security policy.

## Goals / Non-Goals

**Goals:**
- Provide a clear, declarative, and extensible `LockScreenActionMatrixPolicy` implementing `ToolAccessPolicy`.
- Unconditionally allow all tools when the device is `UNLOCKED`.
- When the device is `LOCKED`, permit safe actions:
  - Phone calls and dialing (`call_contact`, `dial_number`, `lookup_contact`, `select_contact`).
  - Device utilities and status queries (`get_current_time`, `get_battery_status`, `get_wifi_state`, `get_bluetooth_state`, `get_ram_usage`).
  - Common hands-free conveniences (`set_timer`, `set_alarm`, `media_control`, `toggle_flashlight`, `ask_user`).
- When the device is `LOCKED`, restrict sensitive and interactive tools:
  - App launching (`open_app`).
  - System settings activities (`open_wifi_settings`, `open_bluetooth_settings`).
  - Private user history and memories (`search_chat_history`, `remember_fact`).
- Generate clear, natural spoken/text denial messages directing the user to unlock their device (e.g., "Please unlock your phone to open YouTube.").
- Replace `AllowAllToolAccessPolicy` in `AppModule` with `LockScreenActionMatrixPolicy`.

**Non-Goals:**
- Per-user customizable lock-screen permissions UI in settings (deferred to future setting screen feature).
- Biometric prompt integration inside Loki (the user will unlock via Android's native keyguard/lock screen).

## Decisions

1. **Default Allowlist vs Denylist for Lock Screen**
   - *Decision:* Use an explicit allowlist of tool names permitted on the lock screen. Any tool not explicitly in the allowlist is denied by default (`fail-closed`).
   - *Rationale:* Secure-by-default. If a new tool is introduced in the future, it cannot accidentally be executed on the lock screen until audited and added to the allowlist.
   - *Alternatives considered:* Denylist (deny only `open_app`, `search_chat_history`). Rejected because newly added tools would default to accessible on the lock screen.

2. **Calling Allowed on Lock Screen**
   - *Decision:* `call_contact`, `dial_number`, `lookup_contact`, and `select_contact` are included in the lock-screen allowlist.
   - *Rationale:* Placing a phone call or looking up a contact for an outgoing call is an essential emergency/hands-free assistant capability permitted by major assistants (Google Assistant, Siri, Gemini) and does not represent a data exfiltration leak.

3. **Contextual Denial Messages**
   - *Decision:* Provide action-aware denial explanations using argument inspection when available. For instance, for `open_app` with `app_name: "YouTube"`, return `"Please unlock your phone to open YouTube."` instead of a generic `"Access denied"`.
   - *Rationale:* `ConversationSession` and `AssistantSession` speak this message directly to the user when tool execution is denied. A clear prompt lets the user know exactly why the action did not proceed.

4. **Policy Instantiation and DI**
   - *Decision:* Provide `LockScreenActionMatrixPolicy.defaultPolicy()` (or constructor with sensible defaults) and inject it via `AppModule`.
   - *Rationale:* Keeps unit tests flexible (tests can pass custom sets of allowed tools) while keeping DI simple.

## Risks / Trade-offs

- [Risk] A contact lookup on lock screen might display contact phone numbers in assistant responses.
  → Mitigation: The assistant only speaks or executes the call. For Phase 2, calling and phone lookups are intentionally permitted per product requirements.
- [Risk] A user attempts to launch an app while locked and experiences turn termination.
  → Mitigation: The assistant speaks `"Please unlock your phone to open <app>."` cleanly, without crashing or infinite re-prompt loops.
