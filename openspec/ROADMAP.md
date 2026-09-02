# Loki UI & Voice Roadmap

Target: a Gemini-style, Material 3 Expressive experience for Loki. Dynamic Color
(Gemini-like, always-on) confirmed as the design direction; brand palette remains
only as API 29/30 fallback. Product: local-first, private, offline-first assistant.

## Implemented & archived

| Change | Delivers |
|---|---|
| `modernize-ui-foundation` | Compose BOM current; Dynamic Color (API 31+); type/shape/spacing tokens; edge-to-edge |
| `modernize-chat-surface` | Bubble-less markdown messages; token streaming; thinking dots; expandable tool cards |
| `modernize-composer` | Floating pill composer; morphing send/mic/stop; generation cancellation |
| `persist-conversation-history` | Durable multi-conversation JSON store; startup restore; write-through turns |
| `modernize-app-shell` | Nav drawer + recents; Settings screen; decluttered top bar; model-status popover |
| `modernize-home-motion` | Greeting home state + chips; vector icons; screen transitions; token sweep |
| `fix-chat-input-and-voice` | IME adjustResize; capability-driven chat voice (DirectAudio + whisper-STT + one-tap ASR provisioning); `core/theme` extraction |
| `voice-visualizer` | Gemini-style live amplitude equalizer; vector icons in voice overlay |

All archived and validated. Spec library is in canonical format (openspec/specs/).

## Backlog (open)

### Code / UI
- **System back navigation** (Bug): back from Settings/Model-Library/Playground/
  Permissions exits the app instead of returning to chat — MainActivity enum
  navigation has no BackHandler/back-stack.
- **Fresh-install / landing page** (Bug): app opens the previous chat instead of a
  new-chat home; a conversation is created eagerly at startup (before user
  interaction), and allowBackup=true restores conversation files on reinstall.
  Requirement: land on empty home by default; create a conversation only on first
  message; previous chats reachable via drawer recents.
- **Voice overlay token sweep**: LokiVoiceInteractionSession.kt still has
  hardcoded fontSize = 15/16/18.sp; migrate to MaterialTheme.typography tokens.
- **Model-switcher dropdown** in chat (badge currently opens an info popover only).
- **Conversation rename UI** (store API exists; drawer UI missing).
- **Conversation retention cap** (full history kept per file indefinitely).
- **Hilt-ify ViewModels** (ChatViewModel/SettingsViewModel/AgentPlaygroundViewModel
  still constructed manually in MainActivity @HiltViewModel + hiltViewModel()).

### Voice
- **Start-speaking audio cue** (Bug, FIXED in `fix-startup-navigation-voice`): a short runtime-synthesized Gemini-like attention tone now plays on voice start (chat mic or assistant long-press). A hidden `audioStartCueEnabled` flag gates it.
- **(Backlog follow-up) Voice start-cue customization**: let the user pick/select a start tone (or disable). Currently a fixed runtime-synthesized tone is shipped for v1.
- **STT streaming partials / optional Android STT engine**: quality/UX upgrade,
  privacy trade-off documented(prefer Whisper offline by default).
- **(Parked) Audio capability layer (later, separate change)**: a runtime
  audio-discovery/front-end for he voice input path: probe `AcousticEchoCanceler` /
  `NoiseSuppressor` / `AutomaticGainControl` via `isAvailable()` + `create(active session)`
  (and `AudioManager.getDevices()` input route/sample-rate); choose an input preset
  (`VOICE_RECOGNITION` / `VOICE_COMMUNICATION` bundle AEC/NS/AGC opaquely) vs explicit
  effects vs raw `MIC`; replace the blind post-TTS delay with a continuous armed-mic +
  barge-in commit window gated on `tts.isSpeaking`/`onDone`; VAD stays Loki-owned
  (Silero-on-device model needed → else existing energy VAD — no public Android
  VAD-as-boolean probe exists). Architecture sketched in the `fix-voice-confirmation-and-persist-answer` change
  discussion. Parked so the front-end fix can ship first without he audio-stack redesign.

### Release / repo hygiene
- **Tag v0.1.0** and publish first GitHub release (workflow ready; debug-signed APK
  without keystore secrets).
- **Add a LICENSE** (README omits the badge until then).
- **Branch protection rules** for `main` (CI gates PRs).

## Capturing plan (grouping)

- **Single combined** → `fix-startup-navigation-voice`: all three bugs together. (Bug 1 system back, Bug 3 landing/lazy conversation — already coupled in the shell skeleton; Bug 2 voice-start cue is independent but trivial and lives in the same startup voice path, so it ships alongside to avoid a second review cycle.) Rationale: keeps a coherent "app boots and talks to the user correctly" change; each bug still has isolated tasks for rollback.


## Decisions so far

- Dynamic Color always-on, no opt-out toggle.
- Foundation first; default landing = new-chat home state, no eager conversation.
- Voice is private-offline: whisper by default; Android STT only as opt-in engine.
- nav + startup are combined because they share the shell/back-stack skeleton.
