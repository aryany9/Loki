## Context

Loki is a greenfield Android voice assistant with no existing codebase. The project's central premise is that Android's system-level `VoiceInteractionService` APIs, combined with on-device LLM inference (llama.cpp / GGUF quantized models) and local STT (Whisper.cpp), are mature enough to build a reliable offline-first voice assistant. Three technical spikes will validate this premise before full implementation. All design decisions in this document assume the spikes succeed.

The primary development device is a Samsung Android phone. All design decisions must use standard Android APIs so that the architecture functions correctly on non-Samsung devices as well.

## Goals / Non-Goals

**Goals:**
- Establish the complete layered architecture for Loki from Android system integration down to tool execution
- Define the module boundaries and interfaces that all future contributors will build on
- Produce a working end-to-end prototype: lock-screen invocation → STT → LLM → tool call → TTS
- Validate the three highest-risk technical assumptions via spikes before committing to full implementation
- Ensure every major subsystem (LLM, STT, TTS, tools) is behind an abstraction that permits swapping implementations without cascading changes

**Non-Goals:**
- Always-on wake-word detection ("Hey Loki") — deferred as an optional future subsystem
- Online tools (web search, weather, prices) — interfaces defined but no implementations
- Screen context via `AssistStructure` — architecture reserves space but not implemented
- WhatsApp or third-party app integration
- Multi-device or cloud sync
- iOS or any non-Android platform
- Full-screen main application UI beyond the minimal session overlay

---

## Decisions

### Decision 1: Android Assistant via `VoiceInteractionService` (not a mic-button app)

**Choice**: Implement Loki as a real Android Assistant using `ROLE_ASSISTANT`, `VoiceInteractionService`, `VoiceInteractionSessionService`, and `VoiceInteractionSession`.

**Rationale**: The README and exploration confirmed the intended UX is lock-screen invocation via system-provided assistant mechanisms (long-press home, power-button gesture where supported). A conventional app with a microphone button does not satisfy this requirement. `VoiceInteractionService` is the standard Android mechanism for becoming the default assistant since API 21 and is well-supported at API 29+.

**Alternative considered**: A foreground service with a floating button overlay. Rejected because it requires the user to open the app first, cannot be invoked from the lock screen via hardware gestures, and is not recognized by Android as an assistant.

**Key implication**: The session window is not a standard Activity. Compose UI is mounted manually via `ComposeView` inside the session window. `FLAG_SHOW_WHEN_LOCKED` and `FLAG_DISMISS_KEYGUARD` control lock-screen visibility (validated in Spike 1).

---

### Decision 2: LLM as Tool Router, Not Chatbot

**Choice**: The LLM's role is structured tool-call generation, not free-form conversation. The output contract is always either a tool call (JSON) or a final natural-language response.

**Rationale**: Loki's value proposition is controlling Android through voice. Every request ultimately maps to an Android action. Framing the LLM as a router/reasoner rather than a chatbot focuses model selection, prompting, and grammar-constraint design on what actually matters: correct tool name + arguments.

**Alternative considered**: Free-form LLM responses with post-hoc intent classification. Rejected because reliability is unpredictable and harder to test.

**Key implication**: The system prompt must describe available tools concisely. Tool definitions must be kept compact to preserve context budget on small models (see Grammar Builder decision).

---

### Decision 3: Grammar-Constrained Output via GBNF (llama.cpp)

**Choice**: Use llama.cpp's GBNF grammar-constrained sampling to guarantee syntactically valid tool calls. The grammar is generated dynamically from `ToolRegistry` at runtime by `GrammarBuilder`.

**Rationale**: Small quantized models (1–4B parameters) frequently produce malformed JSON or hallucinate tool names without constraints. GBNF constrains the token sampler so that invalid output is mathematically impossible. This directly solves the most important reliability risk for Loki's architecture.

**Alternative considered**: Prompt-only JSON mode (instructing the model to "output JSON"). Rejected as unreliable on small models. Schema-based post-processing with retries also considered but adds latency and complexity.

**Key implication**: Grammar-constrained generation is a llama.cpp-specific feature. The `LlmEngine` abstraction must support an optional `outputGrammar: String?` parameter so that a `LiteRtLlmEngine` backend can either implement an equivalent mechanism or fall back to prompt-only JSON mode. The `ConversationManager` must not assume grammar constraints are always available.

---

### Decision 4: `LlmEngine` Abstraction with llama.cpp as First Backend

**Choice**:
```
LlmEngine (interface)
├── LlamaCppLlmEngine    ← first implementation (Spike 2)
└── LiteRtLlmEngine      ← second candidate, evaluated after Spike 2
```

**Rationale**: llama.cpp is selected as the first backend because: (1) GBNF grammar constraints are native, (2) the GGUF ecosystem provides the widest selection of small mobile-optimized models, (3) mature Android JNI ports exist (e.g., llama.cpp Android demo, IRIS), and (4) it is MIT-licensed with a healthy community. LiteRT-LM (Google AI Edge) is retained as a second candidate because of Google's strong NPU/hardware-acceleration support on Android and the relevance of Gemma models. Neither backend concept must leak into `ConversationManager`, `ToolRegistry`, or any layer above `LlmEngine`.

**Alternative considered**: MediaPipe LLM Inference API. Rejected in favor of LiteRT-LM, which is MediaPipe's successor per Google's current direction.

---

### Decision 5: `SttEngine` Abstraction with Whisper.cpp as First Backend

**Choice**:
```
SttEngine (interface)
├── WhisperSttEngine     ← first implementation (Spike 3)
└── AndroidSttEngine     ← optional future backend (may use cloud)
```

**Rationale**: Android's built-in `SpeechRecognizer` may silently fall back to Google's cloud recognition depending on device configuration, violating Loki's offline-first contract. Whisper.cpp tiny/base models provide reliable on-device transcription at acceptable latency. The abstraction allows a future `AndroidSttEngine` backend for users who prefer it explicitly.

**STT approach**: VAD-gated batch transcription (Option A). Mic → VAD silence detection (~300–500ms) → Whisper batch inference → transcript. This is simpler, more accurate, and more reliable than sliding-window streaming. The `SttEngine` interface exposes partial events during VAD and a final transcript event, making the interface streaming-capable without requiring the first backend to implement streaming Whisper.

---

### Decision 6: Multi-Turn ConversationManager with Bounded Agent Loop

**Choice**: `ConversationManager` implements a ReAct-style bounded agent loop from day one, with an explicit fast path for single-tool requests.

```
User utterance
    │
    ▼
[LLM pass 1]
    │
    ├─ tool call → execute → append result to context → [LLM pass N] (up to maxIterations)
    │
    └─ final response → TTS

Fast path (single tool, unambiguous result):
    tool result → deterministic template response → TTS (no second LLM pass)
```

**Rationale**: The "Call Rahul → two matches → clarification → call" example is a first-class use case documented in the README. Building single-turn first and retrofitting multi-turn later would require significant redesign of context management, history, and the tool result feed-back loop. Building it correctly from the start costs little extra effort at the interface design stage.

**Safeguards**:
- `maxIterations`: configurable ceiling on tool calls per user turn (default: 5)
- `toolTimeoutMs`: per-tool execution timeout
- Full cancellation propagated through coroutine `CoroutineScope` / `Job` cancellation
- Context budget tracker: warns and truncates history before hitting model context limit
- Session reset on new invocation

---

### Decision 7: Structured `ToolResult` (not raw strings)

**Choice**: All tools return a typed `ToolResult` containing success/failure status and structured data (serialized as JSON for LLM context injection).

**Rationale**: Unstructured string results force tools to generate natural-language responses, which mixes concerns, reduces testability, and makes it harder for the LLM to reason over multiple tool results. Structured results allow `ConversationManager` to decide whether a second LLM pass is needed for natural-language synthesis.

---

### Decision 8: Minimum SDK API 29, Compile SDK Latest Stable

**Choice**: `minSdk = 29`, `compileSdk` = latest stable at implementation time, `targetSdk` = latest stable.

**Rationale**: API 29 (Android 10) introduced the modern Assistant Role APIs. The target user base (running local LLMs) requires capable hardware, which implies modern Android versions. API 29 covers ~95%+ of active Android devices as of 2025. Minimum SDK can be raised if the implementation reveals that specific features require a higher API level.

---

### Decision 9: Module Structure

```
app/                         ← Android app module (manifest, DI wiring, entry points)
core/
  assistant/                 ← VoiceInteractionService, VoiceInteractionSessionService
  conversation/              ← ConversationManager, agent loop, context tracking
  voice/
    stt/                     ← SttEngine interface + WhisperSttEngine
    tts/                     ← TtsEngine interface + AndroidTtsEngine
  llm/                       ← LlmEngine, ModelManager, LlamaCppLlmEngine, GrammarBuilder
  tools/                     ← Tool, ToolRegistry, ToolResult, LocalTool, OnlineTool
  tools/local/               ← MVP tool implementations
  ui/                        ← VoiceInteractionSession Compose overlay
```

Each `core/` submodule is an Android library module with no knowledge of other modules except through interfaces. `app/` is the only module that wires them together (Hilt DI).

---

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| `VoiceInteractionService` behavior varies by OEM / Android version | Spike 1 validates on Samsung device; document any non-standard behavior; test on a second non-Samsung device before v1.0 |
| Small quantized models produce incorrect tool arguments despite grammar constraints | Semantic validation in `ToolRegistry` as a second guard; benchmark 20+ diverse prompts in Spike 2 |
| Whisper STT latency too high for comfortable conversation | Spike 3 benchmarks P50/P90 latency; fallback to smaller Whisper model (tiny) if base is too slow |
| llama.cpp JNI integration complexity and build system overhead | IRIS (nerve-sparks/iris_android) is a working reference; use CMake with prefab or pre-built .so |
| Context window exhaustion in multi-turn loops on 2K-context models | Context budget tracker in `ConversationManager`; keep system prompt + tool definitions < 600 tokens; trim history by dropping oldest tool results first |
| Lock-screen session permissions / keyguard behavior on Samsung | Validate `FLAG_SHOW_WHEN_LOCKED` behavior in Spike 1; document device-specific findings without encoding them in architecture |
| Model storage size (GGUF 1–4B models = 0.5–3GB) | `ModelManager` handles download + storage; user prompted to download model on first launch; default to smallest viable model |
| Battery drain from background audio monitoring | No background audio in MVP (no wake word); microphone opened only during active session |

---

## Open Questions

1. **Which GGUF model performs best for Loki's tool-calling workload?** — Resolved by Spike 2 benchmark (Gemma 3 1B and Qwen2.5 1.5B as starting candidates).
2. **Does Whisper tiny deliver acceptable accuracy for Indian-English accents at MVP-target latency?** — Resolved by Spike 3.
3. **Does LiteRT-LM warrant a full evaluation after Spike 2?** — Proceed with llama.cpp unless Spike 2 reveals a blocking issue (e.g., ARM64 build failure, prohibitive latency). Evaluate LiteRT-LM in a dedicated spike if warranted.
4. **Minimum API raised to 31+?** — Validate in Spike 1 whether API 29 is sufficient for all required `VoiceInteractionService` behaviors, or whether API 31+ APIs significantly simplify the implementation.
