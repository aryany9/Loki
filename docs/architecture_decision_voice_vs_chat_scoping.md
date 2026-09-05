# Architecture Decision Record: Modality-Scoped System Prompts, Memory & Tool Rendering

**Date:** September 5, 2026  
**Status:** Approved / Decision Finalized  
**Author:** Aryan Yadav & Antigravity  
**Target Subsystems:** `core:conversation`, `core:ui`, `core:assistant`, `core:models`

---

## 1. Executive Summary

During on-device testing with a 2B SLM (Small Language Model) running on mobile hardware (NPU/GPU), we encountered repetitive bugs where rich Markdown answers (such as lists and Java code blocks) were streamed to the UI and then suddenly truncated, replaced, or stripped of their formatting. 

Investigation of ADB logcat revealed that these issues were symptoms of a deeper architectural flaw: **forcing the Voice Assistant and the Chat Screen to share the exact same monolithic system prompt and turn-control state machine**. 

The Voice Assistant mechanically requires the model to invoke the `ask_user` tool to re-arm the microphone hardware, whereas the Chat Screen is an interactive visual messenger where users have a keyboard and expect rich Markdown. Enforcing voice turn-taking rules on the chat interface caused the model to append internal protocol JSON (`{"tool": "ask_user", ...}`) to normal answers, triggering parser failures and aggressive corrective retries that stripped Markdown.

To permanently solve this and eliminate device OOM (Out Of Memory) / KV cache exhaustion risks, we designed a **Section-Based Modular Architecture** that decouples Voice and Chat prompts, introduces **Scoped Memories (`MemoryScope`)**, scopes custom user instructions, and upgrades the Chat UI to render clean Markdown alongside collapsible tool execution dropdowns.

---

## 2. Incident Analysis & Root Cause Diagnosis

### Incident 1: Capabilities Overview Message Truncation ("What can you do?")
- **User Action:** Asked *"What can you do?"* in the Chat Screen.
- **Observed Behavior:** The model streamed a detailed, multi-category Markdown explanation of its features. At completion, the message was abruptly erased and replaced by a single-sentence plain summary.
- **Logcat Evidence (`turnId = 61a3fdaa`):**
  - **Iteration 1 Output:**
    ```markdown
    I can assist you with a variety of tasks! Here are some of the things I can do:
    * **Communication:** Contacts, Phone Calls, Messages...
    * **Media Control:** Music...
    * **System Information & Settings:** Battery, Flashlight, Volume...
    * **Scheduling:** Alarms, Calendar...
    * **Information Retrieval:** Web Search, Wikipedia, Weather, Notes...

    What can I do for you right now? {"tool": "ask_user", "arguments": {"text": "What can I do for you right now?"}}
    ```
  - **Parser Result:** `ToolCallParser.parse(raw)` failed with `Malformed(..., "Expected one JSON object")`.
  - **Corrective Retry (Iteration 2):** `ConversationSession` detected `Malformed`, logged an error, and re-prompted the model with:
    > *"Return exactly one JSON object and nothing else. Do not use Markdown, explanations, or additional turns."*
  - **Iteration 2 Output:**
    ```json
    {"response": "I can help you with communication, media control, system information, settings management, scheduling, information retrieval, and app launching. What do you need assistance with?"}
    ```
  - `ChatViewModel` received `ConversationEvent.Completed` with Iteration 2's response, overwriting the streamed message with the short sentence.

---

### Incident 2: Java Code Block Formatting Stripped ("write hello world code in java")
- **User Action:** Asked *"write hello world code in java"* in the Chat Screen.
- **Observed Behavior:** The user expected a syntax-highlighted code block in a card. Instead, the code block disappeared and was replaced by unformatted plain text.
- **Logcat Evidence (`turnId = 30b7f5f1`):**
  - **Iteration 1 Output:**
    ```markdown
    ```java
    public class HelloWorld {
        public static void main(String[] args) {
            System.out.println("Hello, World!");
        }
    }
    ```

    {"tool": "ask_user", "arguments": {"text": "Do you have any other programming questions or need help with something else?"}}
    ```
  - **Parser Failure:** Line 72 of `ToolCallParser.kt` contained a legacy check:
    ```kotlin
    if (trimmed.contains("```") && (!trimmed.startsWith("```") || !trimmed.endsWith("```"))) {
        return ParsedLlmResponse.Malformed(raw, "Expected one JSON object")
    }
    ```
    This check assumed that the *only* reason triple backticks (` ``` `) would appear was if the model wrapped a JSON tool call in ` ```json ... ``` `. Because the output contained ` ```java ` and ended with `}}`, it was immediately flagged as `Malformed`.
  - **Corrective Retry (Iteration 2):** Prompt forced: *"Return exactly one JSON object and nothing else. Do not use Markdown..."*.
  - **Iteration 2 Output:**
    ```json
    {"response": "public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, World!\");\n    }\n}"}
    ```
  - The model strictly obeyed *"Do not use Markdown"*, stripping the ` ```java ` fences. The UI then rendered plain string text instead of a code block.

---

## 3. The Core Architectural Flaws

```
                               ┌──────────────────────────────────────────┐
                               │       Shared Monolithic Prompt           │
                               │   "If you end your turn with a plain     │
                               │    question, the conversation ENDS!      │
                               │    ask_user is the ONLY way..."          │
                               └────────────────────┬─────────────────────┘
                                                    │
                      ┌─────────────────────────────┴─────────────────────────────┐
                      ▼                                                           ▼
         [Voice Assistant Mode]                                          [Chat Screen Mode]
      - Needs ask_user to re-arm mic                                  - User has keyboard; doesn't need ask_user
      - Spoken TTS format                                             - Expects Markdown & code blocks
      - Correct behavior                                              - Model panics, appends {"tool": "ask_user"}
                                                                      - Parser crashes -> Code blocks stripped!
```

1. **The Shared System Prompt Fallacy:**
   Voice Assistant and Chat Screen have fundamentally opposing requirements. In Voice mode, `ask_user` is a hardware trigger. In Chat mode, the user has a keyboard; the conversation never "dies." Telling the model in Chat mode that it *must* invoke `ask_user` to ask a question forces the 2B model to panic and append raw JSON to its Markdown.

2. **KV Cache & Mobile Memory Exhaustion (OOM Risks):**
   On-device SLMs running via LiteRT / MediaPipe on Qualcomm/MediaTek NPUs have rigid KV cache capacities (2,048 or 4,096 tokens). A monolithic prompt that includes base persona, all 18 tool schemas, user custom instructions, voice alias rules, and user memories consumes **800 to 1,000 tokens**. That pins up to 50% of the KV cache *before turn 1*, increasing Time-To-First-Token (TTFT) latency and leading to frequent compaction and OOM crashes.

3. **Single-Tool UI Limitation:**
   `ChatMessage` historically held only a single nullable `toolName: String?` and `toolResult: ToolResult?`. When a turn executed a tool (e.g. `lookup_contact`) and then had conversational output, or when multiple tool steps occurred, the UI could not represent the tool steps cleanly without flattening or corrupting the text.

---

## 4. Architectural Conclusions & Agreed Solution

### A. Section-Based Modular Prompt Assembly (`PromptSection`)
Instead of building a single string, system prompts are constructed using a keyed, modular pipeline. Common instructions reside in a single shared foundation, while modality profiles can **inherit**, **override**, or **remove** any section.

```kotlin
enum class PromptSection {
    PERSONA,
    USER_CUSTOM_INSTRUCTION,
    USER_MEMORIES,
    LANGUAGE,
    DOMAIN_DIRECTIVES,
    INTERACTION_STYLE,
    TOOL_PROTOCOL
}
```

- **Base Layer (`buildCommonSections`)**:
  - Base persona ("You are Loki, a private offline Android assistant...")
  - Language matching directive
  - User custom instructions from Settings (`agentConfig.systemInstruction`)
  - Core domain rules (e.g., auto-lookup contact on call request)
  - Tool execution protocol: `{"tool": "tool_name", "arguments": {...}}`

- **Voice Profile (`applyVoiceProfile`)**:
  - Sets spoken-first TTS style (concise, natural, no markdown code blocks).
  - Enforces `ask_user` turn-taking to re-arm the device microphone.
  - Enforces verbal confirmation before dangerous actions.

- **Chat Profile (`applyChatProfile`)**:
  - Sets visual chat style (encourages rich Markdown, code blocks with language tags, bullet points).
  - Explicitly instructs: **Do NOT call `ask_user`**; ask questions directly in plain text.
  - Excludes `ask_user` from the list of available tools.

---

### B. Scoped User Memories (`MemoryScope`)
To support voice-specific rules (such as phonetic nicknames: *"When I ask you to call anki, call Ankita"*) without cluttering Chat, memories are tagged with a `MemoryScope`:

```kotlin
enum class MemoryScope {
    GLOBAL, // Applies to both Chat and Voice (e.g., "My name is Aryan", "I live in Bengaluru")
    VOICE,  // Voice-only (e.g., "When I ask you to call anki, call Ankita", pronunciation hints)
    CHAT    // Chat-only (e.g., "Always write code in Kotlin with coroutines", formatting rules)
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val source: MemorySource = MemorySource.MODEL_TOOL
)
```

- **In Voice Sessions (`newVoiceSession`)**: Injects `GLOBAL` + `VOICE` memories. Character budget capped strictly at **~300 characters (~75 tokens)** to minimize prefill latency.
- **In Chat Sessions (`newChatSession`)**: Injects `GLOBAL` + `CHAT` memories. Budget up to **800 characters (~200 tokens)** for comprehensive instructions.

---

### C. Scoped Custom Instructions in `AgentConfig`
Settings screen supports modality overrides while maintaining common base instructions:

```kotlin
data class AgentConfig(
    val systemInstruction: String = DEFAULT_SYSTEM_PROMPT, // Shared base instruction
    val voiceInstruction: String = "",                     // Voice-specific instruction/alias
    val chatInstruction: String = "",                      // Chat-specific instruction
    val generationConfig: GenerationConfig = GenerationConfig(),
    val runtimeConfig: RuntimeConfig = RuntimeConfig(),
    val conversationLanguage: String = "auto"
)
```

---

### D. Chat Screen UI Architecture: Dropdown Tool Invocations
The Chat Screen separates **Markdown content** from **Tool executions**:

```kotlin
data class ToolInvocation(
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val result: ToolResult? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,                                        // Pure Markdown (Code blocks, explanations)
    val toolInvocations: List<ToolInvocation> = emptyList(), // Collapsible tool cards
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false
)
```

- **Message Card Rendering in `ChatScreen.kt`:**
  1. **Top Section:** Renders pure Markdown with syntax highlighting (`Markdown(message.text)`).
  2. **Bottom Section:** If tools were executed (`lookup_contact`, `call_contact`, etc.), renders a list of collapsible dropdown cards showing tool name, arguments, and result data.
  3. **No Internal JSON in Text:** The model's internal tool protocol is never rendered as raw text inside the message bubble.

---

### E. KV Cache Optimization & OOM Mitigation Strategy

1. **Prompt Token Reduction:**
   - Pruning `ask_user` and voice rules from Chat saves ~100 tokens.
   - Pruning markdown rules and chat memories from Voice saves ~150 tokens.
   - Voice prompt size drops from ~1,000 tokens to **~350–400 tokens**, cutting Voice TTFT in half.
2. **Adaptive Output Budgets:**
   - **Voice:** `maxOutputTokens = 128` (prevents runaway generation from blowing up KV cache).
   - **Chat:** `maxOutputTokens = 512` (provides ample headroom for full classes and functions).
3. **Isolated Session Lifecycles:**
   - `newVoiceSession()` is strictly ephemeral. Once the audio interaction completes, its KV cache context is reset.
   - `newChatSession()` manages rolling compaction for multi-turn history without leaking into Voice.

---

## 5. Modality Comparison Matrix

| Feature / Behavior | Voice Assistant (`VOICE`) | Chat Screen (`TEXT`) |
| :--- | :--- | :--- |
| **Primary Goal** | Hands-free, low-latency spoken interaction | Visual readability, rich Markdown, code generation |
| **Output Syntax** | Natural conversational speech (no code blocks) | GitHub-flavored Markdown (` ```java `, tables, lists) |
| **Turn Control Tool (`ask_user`)** | **Required** to re-arm hardware microphone | **Disabled / Removed**; user replies via keyboard |
| **Tool Execution Display** | Audio verbalization / repeat-backs | Collapsible dropdown cards below message |
| **Memory Scopes Included** | `GLOBAL` + `VOICE` (e.g. *"anki -> Ankita"*) | `GLOBAL` + `CHAT` (e.g. coding conventions) |
| **Memory Character Budget** | 300 characters (~75 tokens) | 800 characters (~200 tokens) |
| **Output Token Budget** | 128 tokens | 512 tokens |
| **KV Cache Lifecycle** | Ephemeral (resets per interaction) | Multi-turn persistent with rolling compaction |

---

## 6. Implementation Roadmap

1. **Phase 1: Memory & Config Scoping (`core:conversation`, `core:models`)**
   - Add `MemoryScope` (`GLOBAL`, `VOICE`, `CHAT`) to `MemoryEntry` in `MemoryStore.kt`.
   - Add `voiceInstruction` and `chatInstruction` to `AgentConfig`.
   - Add scoped memory retrieval methods to `MemoryStore`.

2. **Phase 2: Section-Based Prompt Assembly (`core:conversation`)**
   - Refactor `ConversationSession.buildCoreSystemPrompt()` into modular sections (`PromptSection`).
   - Implement `buildCommonSections()`, `applyVoiceProfile()`, and `applyChatProfile()`.
   - Scope available tools: exclude `ask_user` when `source == "TEXT"`.

3. **Phase 3: Chat UI & Multi-Tool Dropdowns (`core:ui`)**
   - Update `ChatMessage` to hold `toolInvocations: List<ToolInvocation>`.
   - Update `ChatScreen.kt` to render a list of `ToolResultCard` dropdowns below the Markdown message.
   - Update `ChatViewModel` event handling to append tool executions without overwriting message text.

4. **Phase 4: Unit Testing & Verification**
   - Verify Java code generation renders as a code block without triggering retries.
   - Verify contact disambiguation in Voice still triggers `ask_user` and re-arms mic.
   - Verify memory scoping (`VOICE` memories appear in Voice prompt; `CHAT` memories appear in Chat prompt).
