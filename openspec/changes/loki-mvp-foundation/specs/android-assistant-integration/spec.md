## ADDED Requirements

### Requirement: Loki declared as Android Assistant
The application SHALL declare a `VoiceInteractionService` and request the `ROLE_ASSISTANT` role in its Android manifest, enabling users to select Loki as their default digital assistant in Android system settings.

#### Scenario: User sets Loki as default assistant
- **WHEN** the user navigates to Android Settings → Apps → Default apps → Digital assistant
- **THEN** Loki appears as a selectable option
- **AND** selecting Loki makes it the system default assistant

---

### Requirement: System-invocation launches Loki session
The application SHALL respond to Android's system-provided assistant invocation mechanisms (long-press home, power-button gesture where SystemUI supports it, or equivalent standard Android gesture) by starting a `VoiceInteractionSession`.

#### Scenario: Assistant invoked on unlocked device
- **WHEN** the user triggers the system assistant gesture on an unlocked device with Loki set as default
- **THEN** Loki's `VoiceInteractionSession` starts within 500ms
- **AND** the session UI overlay appears

#### Scenario: Assistant invoked on locked device
- **WHEN** the user triggers the system assistant gesture while the device is locked
- **THEN** Loki's `VoiceInteractionSession` starts
- **AND** the session UI appears over the keyguard without requiring device unlock

---

### Requirement: Session lifecycle is clean and interruptible
The `VoiceInteractionSession` SHALL support clean start, active, and termination states, and SHALL release all resources (microphone, audio, model context) when hidden or terminated by the system.

#### Scenario: User dismisses session
- **WHEN** the user swipes away or presses back during an active session
- **THEN** the session terminates cleanly
- **AND** microphone recording stops
- **AND** any in-progress TTS or LLM inference is cancelled
- **AND** no resources are leaked

#### Scenario: System terminates session
- **WHEN** Android hides the voice session (e.g., incoming call, screen off)
- **THEN** the session calls `onHide()` and releases all resources
- **AND** a subsequent invocation starts a fresh session correctly

---

### Requirement: No Samsung-specific or OEM-specific APIs used
The implementation SHALL use only standard Android SDK APIs for assistant integration. Any Samsung-specific behavior encountered during testing SHALL be documented but not encoded into the application code.

#### Scenario: Application runs on non-Samsung Android device
- **WHEN** Loki is installed on a non-Samsung device running Android 10+
- **THEN** all assistant integration behaviors function correctly using standard APIs
- **AND** no runtime errors related to missing OEM APIs occur
