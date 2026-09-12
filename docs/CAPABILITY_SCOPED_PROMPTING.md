# Capability-Scoped Prompting & Tool Visibility in Loki

## 1. Overview & Architectural Principle

Loki enforces strict capability-scoped prompting and application-owned task state:

> **Core Principle**: *LLM interprets → Application validates → Tool executes.*  
> The LLM owns natural-language understanding and response generation. The application owns facts, state, validation, permissions, safety, and tool execution. The LLM must **never** be the source of truth for identities, phone numbers, permissions, or confirmation state.

Rather than using an external intent router or hardcoded keywords, **capability selection is emergent from the ReAct loop**. The session starts with full visibility across available tools and scopes dynamically down to the relevant capability once a domain tool is invoked.

---

## 2. Capabilities & Tool Mappings

Loki currently organizes its 18 local tools across **6 capabilities**:

| Capability | Tools Belonging to It | Description & Scope |
|---|---|---|
| **`general`** *(always visible)* | `get_current_time`<br>`get_battery_status`<br>`remember_fact`<br>`search_chat_history` | **D2 Governance Rule:** Ambient, state-free, context-free tools. Always valid to invoke during any conversation or active task. |
| **`calling`** | `lookup_contact`<br>`select_contact`<br>`call_contact`<br>`dial_number` | Phone call workflows, contact lookup, and multi-turn disambiguation. |
| **`device`** | `toggle_flashlight`<br>`get_wifi_state`<br>`get_bluetooth_state`<br>`get_ram_usage`<br>`open_wifi_settings`<br>`open_bluetooth_settings` | Hardware controls and system toggle settings. |
| **`clock`** | `set_alarm`<br>`set_timer` | Timers and alarm management. |
| **`media`** | `media_control` | Audio/media playback commands (play, pause, next, previous). |
| **`apps`** | `open_app` | Launching installed applications by name. |

---

## 3. The "General" Governance Rule (Decision D2)

`general` is strictly protected from becoming an unmanaged bucket. A tool **MAY** be assigned `general` only if it satisfies all three criteria:
1. **State-free**: It neither reads from nor writes to any `TaskState`.
2. **Context-free**: Invoking it is meaningful during any other capability's active task (e.g. asking *"what time is it?"* mid-contact-selection).
3. **Non-committal**: Executing it never commits the user to a multi-turn task.

Only the 4 tools listed above meet these criteria. Any new tool defaults to its own domain capability.

---

## 4. Capability Lifecycle: Activation & Deactivation

### A. Session Start (`activeCapability = null`)
- When a voice or text turn starts, the user's intent is unknown.
- The model sees the full set of permission-granted tools.
- LiteRT-LM is prefilled once with the static core system prompt (persona, JSON protocol, language directive, memories).

### B. Activation (Emergent Selection)
- The user gives an utterance (e.g. *"Call Mom"* or *"Turn on the flashlight"*).
- The LLM selects a tool. As soon as a domain tool executes (e.g. `lookup_contact` $\rightarrow$ `calling`):
  - `activeCapability` is set to that domain (e.g. `"calling"`).
  - `isActivationTurn` is flagged as `true`.

### C. Scoped Tool Visibility & Grammar Constraints
- On subsequent turns within the loop, available tool schemas and GBNF grammars are restricted to:
  $$\text{Visible Tools} = \text{general} + \text{activeCapability} + \text{advancingTool (if any)}$$
- Out-of-scope tools are omitted from the LLM prompt. If the model generates an out-of-scope tool call anyway, a coached deferral intercepts it without execution:
  - *With unresolved task*: `"Tool '<name>' is unavailable. Please resolve the current task first."*
  - *Without unresolved task*: `"Tool '<name>' is currently unavailable while <capability> is active."*

### D. Advancing Tools (`TaskState`)
- Advancing tools (e.g. `select_contact`) are internal tools.
- They are **hidden** until derived from the application-owned `TaskState`:
  - `selectedId == null` $\rightarrow$ `advancingTool = "select_contact"` (visible).
  - `selectedId != null && !confirmed` $\rightarrow$ `advancingTool = "call_contact"` (`select_contact` hidden).

### E. Deactivation
The active capability clears back to `null` (restoring full tool visibility) when:
1. The advancing task completes (e.g. `call_contact` executes).
2. The model outputs a direct conversational response and no `TaskState` is pending (`taskState == null || taskState.resolved`).
3. The user dismisses or cancels the session.

---

## 5. Dynamic Prompt Injection

When a capability is active, `ConversationSession` injects dynamic guidance per turn:

### For `calling`:
- **Activation Turn (Full Guidance)**:
  > *"Calling guidance: When looking up contacts to call: if multiple contacts match, list them by NAME only. Never speak, display, or invent phone numbers — phone numbers are unavailable to you. Ask the user which contact to call using their name or candidate ID (e.g. using select_contact). If there is a unique match or once selected, ask for verbal confirmation before placing the call."*
- **Subsequent Turns (1-Line Reminder)**:
  > *"Calling reminder: Refer to candidates by name or candidate ID; phone numbers are unavailable to you. Do not execute call_contact until the user confirms."*
- **Fresh TaskState Block (No Phone Numbers In Context)**:
  ```text
  Current Task: Contact Resolution
  Matching candidates:
  - [c1] Mom
  - [c2] Mom Mobile
  Phone numbers are unavailable to you. Ask the user which contact they want to call.
  ```

---

## 6. How to Test on an Android Device

### Prerequisites
1. Build and install the debug APK:
   ```bash
   ./gradlew installDebug
   ```
2. In the Loki app, grant **Contacts** and **Microphone** permissions.
3. Ensure at least two contacts on the device share a name prefix (e.g., `Mom` and `Mom Mobile`).

### Real-Time ADB Log Monitoring
Open a terminal and monitor Loki logs:
```bash
adb logcat -v color -s LokiTurn:V ConversationSession:V LokiAssistant:V
```

### End-to-End Voice Test Flow

1. **Disambiguation Question**:
   - Tap the mic and say: *"Call Mom"*.
   - **Verification**: Assistant asks a question listing matches **by name only** (e.g. *"I found Mom and Mom Mobile. Which one would you like to call?"*). No numbers are spoken or printed.
   - **Logcat**: `lookup_contact` returns matches with IDs (`c1`, `c2`). Capability `calling` activates.

2. **Natural Selection**:
   - In follow-up, say: *"The first one"* or *"Mom Mobile"*.
   - **Verification**: Model emits `select_contact(candidate_id="c1")`. App validates candidate ID against internal state and prompts for confirmation (*"Do you want me to call Mom?"*).

3. **Verbal Confirmation & Call Execution**:
   - In follow-up, say: *"Yes, go ahead"*.
   - **Verification**: Model emits `call_contact(candidate_id="c1")`. App resolves `c1` to the real phone number internally from app state. TTS announces the **contact name** (*"Calling Mom"*), never speaking raw digits. Call intent is launched.

4. **Out-of-Scope Isolation**:
   - During contact selection, say: *"Turn on the flashlight"*.
   - **Verification**: `toggle_flashlight` is out of scope and blocked. Coached deferral guides the model to resolve the contact first.
