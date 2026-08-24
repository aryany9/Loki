# Loki — Open-Source Offline-First Voice Assistant for Android

## Project Overview

**Loki** is an open-source, privacy-focused, offline-first voice assistant for Android devices.

The primary goal of Loki is to provide a **hands-free conversational assistant that can perform useful actions directly on the Android device without requiring an internet connection**.

Loki is inspired by the convenience of assistants such as Google Gemini and other modern AI assistants, particularly the ability to invoke an assistant while the phone is locked and interact with it entirely through voice. However, Loki is intentionally designed around a different philosophy:

> **Local-first. Private by default. Internet only when necessary.**

Instead of sending every user request to a cloud-based AI service, Loki should process as much as possible directly on the device using a **local/mobile LLM**, local speech recognition, Android APIs, and locally available tools.

When a task genuinely requires current online information, Loki may use the internet through explicitly defined online tools.

---

# Core Motivation

The primary motivation for Loki is practical hands-free usage.

A typical scenario is using the phone while riding a motorcycle with earphones connected.

With conventional cloud-based assistants, even simple commands may require an internet connection and communication with remote servers. This introduces latency and reliability problems, particularly in areas with weak or unstable connectivity.

For example, the following commands do not inherently require the internet:

* "Call Rahul."
* "Dial 9876543210."
* "Call back."
* "Open YouTube Music."
* "Pause the music."
* "What's my battery percentage?"
* "Set a timer for 10 minutes."
* "What time is it?"
* "Who just messaged me?"

Loki should be capable of handling these locally.

The internet should only be introduced when it provides meaningful value.

For example:

> "What is the current price of the XUV 200?"

This requires current information and therefore may use an online web-search tool.

---

# Project Philosophy

Loki should follow these principles:

### 1. Local-first

Prefer device-local processing whenever possible.

### 2. Offline-capable

Core assistant functionality should continue working without an internet connection.

### 3. Privacy-first

Voice input, conversations, notifications, contacts, and other sensitive information should remain on-device unless the user explicitly enables functionality that requires sending information externally.

### 4. Tool-oriented

The LLM should not directly control Android.

Instead, the LLM should select from a controlled set of tools, and the Android application should execute those tools.

### 5. Model-agnostic

Loki should not be tightly coupled to a single LLM.

The architecture should allow different compatible local models to be used.

Potential models include mobile-compatible variants of:

* Gemma
* Qwen
* Llama
* Mistral
* Other future on-device models

### 6. Extensible

New capabilities should be implemented as tools/modules rather than being hard-coded into the assistant's core.

### 7. Open source

The project should be fully open source and designed so that other developers can contribute new tools, models, integrations, and improvements.

---

# Primary User Experience

The ideal Loki experience should be:

```text
Phone locked
      ↓
User invokes Loki
      ↓
Loki starts listening
      ↓
User speaks naturally
      ↓
Local Speech-to-Text
      ↓
Local LLM
      ↓
Tool selection / reasoning
      ↓
Android executes action
      ↓
Result returned to LLM
      ↓
Local Text-to-Speech
      ↓
Loki responds
```

Example:

```text
User:
"Hey Loki, call Rahul."

Loki:
"Calling Rahul."

        ↓

Local STT
        ↓
Local LLM
        ↓
call_contact(name="Rahul")
        ↓
Android Contacts API
        ↓
Android Phone API
        ↓
Call initiated
        ↓
Local TTS
```

The entire operation should work without internet access.

---

# Android Assistant Integration

Loki should eventually behave as a **real Android Assistant**, rather than merely being an application containing a microphone button.

The project should investigate and utilize Android's assistant-related APIs, particularly:

* `VoiceInteractionService`
* `VoiceInteractionSessionService`
* `VoiceInteractionSession`
* Android Assistant Role / `ROLE_ASSISTANT`
* Keyguard/lock-screen voice interaction capabilities

The intended experience is similar to invoking Gemini from a Samsung device using the hardware/power-button assistant shortcut:

```text
Phone locked
      ↓
User invokes Loki
      ↓
Loki voice session starts
      ↓
User speaks
      ↓
Assistant responds
```

The exact invocation mechanism may vary by Android version and device/OEM. Loki should use supported Android mechanisms rather than relying on device-specific hacks.

---

# Core Architecture

Loki should be separated into several major layers.

```text
┌──────────────────────────────────────────────┐
│                 Android System               │
│                                              │
│  Assistant Role / VoiceInteractionService   │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│              Voice Interaction               │
│                                              │
│  Microphone → VAD → STT → Conversation      │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                  AI Layer                    │
│                                              │
│       Local LLM / Model Manager              │
│              Tool Calling                    │
└──────────────────────┬───────────────────────┘
                       │
              ┌────────┴────────┐
              │                 │
              ▼                 ▼
      Local Tools          Online Tools
              │                 │
              ▼                 ▼
       Android APIs         Web / APIs
              │                 │
              └────────┬────────┘
                       ▼
┌──────────────────────────────────────────────┐
│              Response Generation             │
│                                              │
│                Local TTS                     │
└──────────────────────────────────────────────┘
```

---

# Recommended Technology Stack

## Android Application

**Kotlin**

Kotlin should be the primary language.

Although Flutter is suitable for conventional Android applications, Loki's core functionality depends heavily on Android-specific capabilities such as:

* `VoiceInteractionService`
* Assistant role
* microphone/audio lifecycle
* foreground/background services
* notifications
* phone calls
* contacts
* media controls
* Android permissions
* lock-screen interaction
* system intents
* Android lifecycle management

Therefore, the core application should be native Android rather than Flutter.

---

# UI

Use:

**Jetpack Compose**

Compose should be used for Loki's user-facing configuration and conversation UI.

The primary interaction is voice, so the UI should remain lightweight.

Potential screens:

```text
Home
Models
Voice
Tools
Permissions
Privacy
Conversation History
Settings
About
```

---

# Local LLM

The LLM should run entirely on the Android device whenever possible.

The architecture should introduce an abstraction such as:

```text
LlmEngine
```

rather than directly coupling application code to a particular inference framework.

Conceptually:

```text
LlmEngine
   │
   ├── GemmaBackend
   ├── QwenBackend
   ├── LlamaBackend
   └── FutureBackends
```

The project should investigate mobile-compatible inference runtimes such as:

* llama.cpp
* MediaPipe / Google AI Edge tooling where appropriate
* Other Android-compatible local inference runtimes

The exact runtime should be selected based on:

* Android compatibility
* CPU/GPU/NPU support
* model compatibility
* memory usage
* inference speed
* quantization support
* licensing
* ease of distribution
* community support

The model itself should not be treated as part of Loki's source code.

Users should ideally be able to select/download/manage compatible models.

---

# Model Management

Loki should eventually provide a model-management layer.

Example:

```text
Model Manager

Active Model:
Qwen [mobile variant]

Available Models:
- Gemma
- Qwen
- Llama
- Other compatible models

Model information:
- Size
- Quantization
- Context length
- RAM requirement
- Recommended device class
```

Loki should avoid assuming that every Android device has sufficient resources for the same model.

Different models should be usable depending on device capabilities.

---

# Speech-to-Text

Speech recognition is a critical component because Loki's primary interaction is voice.

The system should support local/offline speech recognition whenever practical.

Architecture:

```text
Microphone
    ↓
Audio capture
    ↓
Voice Activity Detection
    ↓
Local STT
    ↓
Text
```

Potential technologies/models may include:

* Whisper-family models
* Android-compatible speech recognition runtimes
* Other open-source local STT solutions

The implementation should prioritize:

* low latency
* low memory consumption
* offline operation
* noisy environments
* microphone/earphone usage
* multilingual support

The assistant should eventually work reasonably well with:

* English
* Indian English
* Hindi
* Hinglish
* Additional languages where practical

---

# Text-to-Speech

Loki should support local speech output.

Architecture:

```text
LLM response
     ↓
Text
     ↓
TTS Engine
     ↓
Audio
```

The initial implementation may support Android's system TTS engine.

Later versions may support additional local/open-source TTS engines.

The TTS layer should also be abstracted:

```text
TtsEngine
   │
   ├── AndroidTts
   └── LocalTts
```

---

# Tool System

The **Tool System is one of the most important parts of Loki.**

The LLM should not directly execute Android functionality.

Instead, Loki exposes a controlled set of tools.

Example:

```text
call_contact(name)
dial_number(number)
call_last_number()
open_app(package)
play_media()
pause_media()
next_track()
previous_track()
set_timer(duration)
set_alarm(time)
get_battery_status()
get_current_time()
read_notifications()
web_search(query)
```

The LLM determines which tool is appropriate.

Example:

```text
User:
"Call Rahul."

LLM:
Tool = call_contact
Arguments:
name = "Rahul"
```

Android then executes:

```text
call_contact("Rahul")
```

The result is returned to the assistant:

```text
Contact found: Rahul Yadav
Call initiated successfully.
```

The LLM can then produce:

> "Calling Rahul."

---

# Why Tool Calling Is Important

This architecture provides:

* predictable behavior
* better security
* easier testing
* easier debugging
* model independence
* permission control
* extensibility
* safer execution

The LLM becomes the **reasoning layer**, while Android remains the **execution layer**.

---

# Initial Local Tools

The first version should prioritize actions that do not require internet connectivity.

## Phone

```text
call_contact(name)
dial_number(number)
call_last_number()
```

## Contacts

```text
find_contact(name)
```

## Media

```text
play()
pause()
next()
previous()
```

## Android

```text
open_app()
get_battery_status()
get_time()
set_timer()
set_alarm()
```

## Notifications

```text
get_recent_notification()
get_notifications_from_app()
```

---

# Music

Loki should support media control using Android's supported media APIs.

The first implementation should focus on generic media controls:

```text
Play
Pause
Resume
Next
Previous
```

Loki may also support opening a predefined music application.

Potential applications include:

* YouTube Music
* Spotify
* local music players
* other Android media applications

The project should avoid depending on undocumented/private APIs of third-party applications.

For example:

```text
"Play music."

→ Open/configured music application
→ Use supported media controls
```

---

# Online Capabilities

Online functionality should be implemented as a separate capability layer.

The user should be able to control whether online tools are available.

Example:

```text
Internet Access
────────────────

Online tools: ON

✓ Web Search
✓ Weather
✓ Current Information
✓ Online APIs
```

When disabled:

```text
Online tools: OFF

Loki will only use local capabilities.
```

---

# Online Tool Example

User:

> "What is the current price of the XUV 200?"

Loki determines that this requires current information.

```text
User
 ↓
STT
 ↓
Local LLM
 ↓
Requires current information
 ↓
Web Search Tool
 ↓
Internet
 ↓
Search result
 ↓
LLM
 ↓
TTS
```

The local LLM should remain the central reasoning component even when online tools are used.

---

# Privacy Model

Privacy should be a core feature rather than an afterthought.

By default:

```text
Voice
 ↓
Local STT
 ↓
Local LLM
 ↓
Local Tool
 ↓
Local TTS
```

No cloud request should be required.

When an online capability is required:

```text
Local LLM
 ↓
Online Tool
 ↓
Internet
```

The user should know when this occurs.

Sensitive information such as:

* contacts
* phone numbers
* notifications
* conversation history
* voice recordings

should remain local unless the user explicitly enables functionality requiring external transmission.

---

# WhatsApp Integration

WhatsApp integration is a future feature and should not be part of the initial architecture's assumptions.

Potential functionality:

```text
"Who messaged me?"

"Read the latest WhatsApp message."

"Who sent me the latest message?"
```

Android's notification system may allow Loki to process WhatsApp notification content after the user explicitly grants notification-listener access.

However, Loki should **not depend on brittle UI automation or unauthorized reverse engineering of WhatsApp**.

Sending messages is more complicated.

Potential future implementation:

```text
"Send Rahul a WhatsApp message saying
I'll reach home in 10 minutes."
```

This should only be implemented using legitimate Android/WhatsApp-supported mechanisms where available.

Accessibility-based UI automation should not be considered the default architecture for this feature.

---

# Future Features

Potential future functionality includes:

### Phone

```text
"Call back."

"Call the last number."

"Call Mom."

"Dial this number..."
```

### Messaging

```text
"Who messaged me?"

"Read the latest message."

"Send Rahul a message..."
```

### Media

```text
"Play music."

"Pause."

"Next song."

"Play my workout playlist."
```

### Android

```text
"Turn on flashlight."

"Set an alarm."

"Set a timer."

"What's my battery?"

"Open WhatsApp."
```

### Information

```text
"What is the weather?"

"Search for..."

"What is the current price of..."
```

### Conversation

Loki should eventually support multi-turn conversations:

```text
User:
"Call Rahul."

Loki:
"Which Rahul?"

User:
"Rahul Yadav."

Loki:
"Calling Rahul Yadav."
```

The assistant should maintain enough conversational context to handle natural follow-up commands without requiring the user to repeat everything.

---

# MVP

The first milestone should be deliberately small.

The MVP should prove the fundamental architecture rather than attempt to reproduce Gemini.

### MVP goal

A user should be able to invoke Loki, speak naturally, have the request processed locally, and perform useful Android actions.

Example:

```text
Phone locked
     ↓
Invoke Loki
     ↓
"Call Rahul"
     ↓
Local STT
     ↓
Local LLM
     ↓
Contact lookup
     ↓
Phone call
     ↓
"Calling Rahul."
```

Initial supported commands:

```text
Call a contact
Dial a number
Open an application
Get battery percentage
Get current time
Set a timer
Set an alarm
Play/pause/skip media
```

All of these should work without an internet connection.

---

# Suggested Development Phases

## Phase 1 — Android Assistant

Prove:

* Android Assistant Role
* `VoiceInteractionService`
* Voice session
* Microphone
* Lock-screen interaction
* Basic TTS

No LLM required initially.

---

## Phase 2 — Local Voice Pipeline

Implement:

```text
Microphone
→ STT
→ text
→ TTS
```

Validate latency and reliability.

---

## Phase 3 — Local LLM

Add:

```text
STT
→ Local LLM
→ TTS
```

Initially use simple conversational responses.

---

## Phase 4 — Tool Calling

Add:

```text
Local LLM
→ Tool selection
→ Android tool
→ Result
→ LLM
→ TTS
```

Implement phone, contacts, media, timers, alarms, etc.

---

## Phase 5 — Online Tools

Add optional:

* web search
* weather
* current information
* other APIs

Online capabilities must remain optional.

---

## Phase 6 — Advanced Android Integration

Investigate:

* notifications
* recent calls
* media applications
* WhatsApp notification reading
* deeper Android integrations
* more system controls

---

# Security and Permissions

Loki will potentially have access to highly sensitive Android capabilities.

Permissions should therefore be:

* requested only when needed
* clearly explained
* independently controllable
* revocable by the user

Examples include:

```text
RECORD_AUDIO
READ_CONTACTS
CALL_PHONE
READ_CALL_LOG
POST_NOTIFICATIONS
Notification Listener access
```

Loki should avoid requesting every permission during initial setup.

Instead:

```text
User says:
"Call Rahul"

→ Loki asks for Contacts permission if required.

User enables it.

→ Loki performs the operation.
```

---

# Open-Source Design

The repository should be structured so that contributors can independently work on:

```text
LLM backends
STT engines
TTS engines
Android tools
Online tools
UI
Model management
Device optimization
Languages
```

The core architecture should not assume a single model, inference engine, or speech engine.

---

# Model and Runtime Abstraction

The application should define interfaces similar conceptually to:

```text
LlmEngine
SttEngine
TtsEngine
Tool
ToolRegistry
ModelManager
```

For example:

```text
interface LlmEngine {
    generate(...)
    stream(...)
    generateToolCall(...)
}
```

and:

```text
interface Tool {
    name
    description
    parameters
    execute(...)
}
```

The exact implementation can evolve as the project develops.

---

# Performance Requirements

Since Loki targets mobile devices, performance is critical.

The system should prioritize:

* low memory usage
* low CPU usage
* efficient model quantization
* hardware acceleration where available
* fast startup
* low audio latency
* streaming responses
* minimal battery consumption

The assistant should avoid unnecessarily loading a large model into memory when a smaller model can accomplish the task.

A particularly useful future optimization would be **different models for different tasks**.

For example:

```text
Simple command
     ↓
Small model

Complex question
     ↓
Larger model
```

---

# Target Interaction

The ultimate experience should feel conversational rather than like a chatbot.

For example:

```text
User:
"Hey Loki."

Loki:
"Yes?"

User:
"Call Rahul."

Loki:
"Calling Rahul."

[Call begins]
```

Or:

```text
User:
"Hey Loki."

Loki:
"Yes?"

User:
"What's the price of the XUV 200?"

Loki:
"Let me check."

[Online search]

Loki:
"The latest price I found is..."
```

The user should not need to understand whether the request was processed locally or remotely.

Loki should make that decision automatically based on the required capability.

---

# Guiding Principle

The central design principle is:

> **Think locally. Act locally. Go online only when necessary.**

Loki should not attempt to replace every feature of Gemini immediately.

Instead, it should become extremely good at one specific thing:

> **Giving users a fast, private, hands-free way to control and interact with their Android phone using natural language.**

---

# Project Identity

**Name:** Loki

**Type:** Open-source Android voice assistant

**Primary focus:** Offline-first, on-device AI

**Platform:** Android

**Primary language:** Kotlin

**UI:** Jetpack Compose

**AI:** Local/mobile LLMs

**STT:** Local speech recognition

**TTS:** Local/system TTS

**Architecture:** Tool-based agent architecture

**Cloud:** Optional, only for capabilities that require current online information

**License:** To be decided before implementation

**Target users:** Android users who want a private, fast, hands-free assistant that continues working without an internet connection.

### Short project description

> **Loki is an open-source, offline-first voice assistant for Android that uses on-device AI to understand natural language and perform actions on your phone, while optionally using the internet only when a task requires current online information.**
