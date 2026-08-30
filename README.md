<div align="center">

# ⚡ Loki

**Open-source, offline-first AI assistant for Android**

*Local-first. Private by default. Internet only when necessary.*

[![Platform](https://img.shields.io/badge/platform-Android_10%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android&logoColor=white)](https://developer.android.com/compose)
[![LiteRT-LM](https://img.shields.io/badge/On--Device_LLM-LiteRT--LM-F9AB00)](https://ai.google.dev/edge/litert)
[![Build](https://img.shields.io/badge/build-Gradle_KTS-02303A?logo=gradle&logoColor=white)](#getting-started)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)](#contributing)

</div>

---

Loki is a privacy-focused, offline-first voice and chat assistant that runs **entirely on your device**. Powered by an on-device LLM (LiteRT-LM) and local speech recognition, it understands your requests and takes real actions on your Android phone — calls, alarms, timers, media, and more — without sending a single byte to the cloud.

> Invoke it hands-free from the lock screen, talk to it like an assistant, or chat with it in a modern Gemini-style interface. It works in airplane mode.

## ✨ Features

**🧠 On-device intelligence**
- Local LLM inference via **LiteRT-LM** — no cloud, no API keys, no telemetry
- Structured tool-calling with constrained-decoding grammars
- Streaming, token-by-token responses rendered as Markdown

**🗣️ Voice, hands-free**
- Lock-screen invocation via Android Voice Interaction Services
- Offline speech recognition (Whisper) with VAD
- Text-to-speech responses in voice mode; quiet text mode in chat

**🛠️ Real actions, not just answers**
- Built-in tools: calls, dialing, contacts, alarms, timers, battery status, media control, app launching
- Permission-aware execution — tools only act when you've granted access

**💬 A modern chat experience**
- Gemini-style navigation drawer with conversation history
- Wallpaper-based Dynamic Color (Material You) on Android 12+
- Floating composer with morphing send / mic / stop button — interrupt generation anytime
- Persistent multi-conversation history that survives restarts
- Settings screen with light / dark / system theming


## 🏗️ Architecture

Loki is a modular Android project. Each capability lives in its own Gradle module:

```
app/                          Application entry, navigation, DI wiring
├── core/
│   ├── assistant/            System assistant integration (voice sessions)
│   ├── conversation/         ConversationManager, sessions, persistence, tool-call parsing
│   ├── llm/                  LLM engine abstraction + LiteRT-LM implementation
│   ├── models/               Model library: catalog, downloads, validation, manifest
│   ├── tools/                Tool registry & permission-aware execution
│   │   └── local/            Built-in on-device tools
│   ├── ui/                   Jetpack Compose UI: chat, drawer, settings, theming, tokens
│   └── voice/
│       ├── stt/              Offline speech recognition (Whisper + VAD)
│       └── tts/              Text-to-speech output
```

```
 ┌──────────┐   text/voice   ┌──────────────────────┐   tool calls   ┌────────────┐
 │ Composer │ ─────────────▶ │  ConversationManager │ ─────────────▶ │   Tools    │
 │  & Voice │                │  ┌─────────────────┐ │                │ (calls,    │
 └──────────┘                │  │    LLM Engine   │ │                │  alarms…)  │
                             │  │   (LiteRT-LM)   │ │                └────────────┘
                             │  └─────────────────┘ │
                             │  ConversationStore   │  ◀── durable JSON history
                             └──────────────────────┘
```

## 🚀 Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android SDK 35
- A physical device or emulator on **Android 10 (API 29)+** — Dynamic Color requires Android 12+

### Build & run

```bash
git clone git@github.com:aryany9/Loki.git
cd Loki
./gradlew :app:installDebug
```

Or open the project in Android Studio and press **Run**.

### Set up a model

1. Launch Loki — the setup screen guides you through model provisioning
2. Pick a model from the bundled catalog or import your own `.litertlm` / GGUF artifact
3. Grant tool permissions (contacts, alarms, phone) when prompted

### Set Loki as your assistant

To enable hands-free, lock-screen invocation, set Loki as the default assistant:
**System Settings → Apps → Default Apps → Digital assistant app → Loki**

See [`docs/SPIKE_1_VALIDATION.md`](docs/SPIKE_1_VALIDATION.md) for a step-by-step device validation guide.

## 📦 Model Library

Models are managed with an explicit lifecycle — `NOT_DOWNLOADED → DOWNLOADED → LOADED` — and stored in a versioned JSON manifest with SHA-256 integrity checks and atomic writes. Import your own artifacts or download from the bundled catalog. Details in [`docs/MODEL_LIBRARY.md`](docs/MODEL_LIBRARY.md).

## 🗺️ Roadmap

- [x] On-device LLM chat with streaming & tool calling
- [x] Lock-screen voice assistant integration
- [x] Modern Gemini-style UI — Dynamic Color, drawer, settings, composer
- [x] Persistent multi-conversation history
- [ ] True model-switcher dropdown in chat
- [ ] Online tools for web-dependent queries
- [ ] Conversation search & export

## 🤝 Contributing

Contributions are welcome! Whether it's a new on-device tool, a UI polish, or an engine improvement:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-thing`)
3. Commit your changes and open a Pull Request

Specs for existing capabilities live in [`openspec/specs/`](openspec/specs/) — check them before proposing behavior changes.

## 📚 Documentation

| Document | Description |
|---|---|
| [`docs/README.md`](docs/README.md) | Project overview & core motivation |
| [`docs/MODEL_LIBRARY.md`](docs/MODEL_LIBRARY.md) | Model lifecycle, manifest & validation |
| [`docs/Loki_LiteRT_LM_From_Scratch_Implementation.md`](docs/Loki_LiteRT_LM_From_Scratch_Implementation.md) | LiteRT-LM implementation deep-dive |
| [`docs/loki-assistant-openspec-context.md`](docs/loki-assistant-openspec-context.md) | OpenSpec project context |

---

<div align="center">

**Built with ⚡ for a private, offline AI future**

</div>
