# Spike 1: Android Assistant & Lock-Screen Invocation Validation Guide

This guide describes how to validate Spike 1 (Tasks 1.2 to 1.6) on your physical Android device (e.g. Samsung Galaxy) or an emulator.

---

## 1. Install the APK

Connect your phone via USB with USB debugging enabled, or start an emulator, then install the debug build:

```bash
./gradlew installDebug
# or directly with adb:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Set Loki as Default Assistant (Task 1.2)

1. Open the **Loki** app from your launcher.
2. Tap **"Open Default Assistant Settings"** (or go to **Settings** → **Apps** → **Choose default apps** → **Digital assistant app**).
3. Tap **Device assistant app**.
4. Select **Loki** from the list of available assistant apps.
5. Tap **Agree / OK** if prompted by the system warning dialog.

---

## 3. Test System Assistant Invocation (Task 1.3)

With the device unlocked:
- **Navigation Buttons**: Long-press the **Home button**.
- **Gesture Navigation**: Swipe in diagonally from either bottom corner (or long-press the nav bar / power button depending on device setup).
- **Samsung Side Button**: If configured in Settings → Advanced Features → Side button to launch Assistant.

**Expected Result:**
- The Loki bottom overlay appears with:
  - ⚡ *Loki Assistant*
  - *Spike 1 — VoiceInteractionService Active*
  - *Listening...*
  - A *Dismiss Session* button.

---

## 4. Test Lock-Screen Invocation (Task 1.4)

1. Lock your device.
2. Turn on the screen without unlocking.
3. Trigger the assistant shortcut (e.g. Power button long-press or corner swipe on lock screen).

**Expected Result:**
- Loki's session overlay appears directly over the lock screen without prompting for PIN/Fingerprint.

---

## 5. Test Cancellation & Cleanup (Task 1.5)

1. Tap **"Dismiss Session"** or press the Back button / swipe back.
2. Verify the session disappears and logs confirm clean teardown.

**Log inspection:**
```bash
adb logcat -s LokiVIS LokiVISS LokiSession
```

You should observe the clean lifecycle sequence:
```text
LokiVIS: LokiVoiceInteractionService ready — Loki is the active Android assistant
LokiVISS: Creating new LokiVoiceInteractionSession with args: ...
LokiSession: onCreate()
LokiSession: onCreateContentView()
LokiSession: onShow() showFlags=..., args=...
LokiSession: onHide()
```

---

## 6. Documenting OEM Behavior (Task 1.6)

Note any device-specific behaviors (e.g., Samsung Bixby side-button mappings or lock-screen permission dialogues) to ensure standard Android APIs remain the single foundation.
