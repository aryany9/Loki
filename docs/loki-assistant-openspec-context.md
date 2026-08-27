# Loki Android Assistant — Architecture & OpenSpec Decision Context

## Purpose of this document

This document records the decisions, requirements, architectural direction, discoveries, constraints, and unresolved implementation details discussed for the Loki Android project.

**Important:** This is the source-of-truth context for an OpenSpec proposal. The next agent should treat these decisions as intentional requirements and should **not re-invent, weaken, or silently change them**.

The goal is to allow another coding/architecture agent to produce an OpenSpec proposal without relying on conversational memory.

---

# 1. Product Vision

Loki is intended primarily to be a **local Android AI assistant**, not merely a local chatbot.

The long-term goal is to have Loki act as a local/on-device alternative to an assistant such as Gemini, where the user can issue assistant commands such as:

- Play music
- Call someone
- Open applications
- Perform Android/device actions
- Use tools to interact with the device
- Answer normal questions
- Have conversational interactions
- Maintain conversation memory where appropriate

The app is intentionally **hybrid**:

1. **Voice mode** is assistant-oriented and action-oriented.
2. **Chat mode** is conversational and should use memory/context more heavily.

A key behavioral requirement is:

- **Voice mode should not automatically pull from long-term conversational memory** merely because memory exists.
- **Chat mode should use memory**, similar to how a general conversational AI behaves.

This distinction should be represented in the eventual agent/runtime architecture rather than treating all conversations as identical.

---

# 2. Primary LLM Runtime Decision

## Decision: LiteRT-LM is the PRIMARY runtime

The project currently has both:

- LiteRT-LM / MediaPipe-based inference
- llama.cpp / GGUF inference

The deliberate decision is:

> **LiteRT-LM should be the primary runtime.**

The motivation is expected performance/efficiency on Android, particularly for an assistant that needs responsive local inference.

llama.cpp/GGUF should remain available as a **secondary runtime**, especially because the existing llama.cpp path already contains grammar/GBNF infrastructure.

Do NOT reverse this decision simply because LiteRT-LM currently has weaker Java-level structured-output APIs.

Instead, the architecture must accommodate LiteRT-LM's current limitations while leaving room for future runtime improvements.

---

# 3. LiteRT-LM / MediaPipe Investigation

The project currently uses:

```text
com.google.mediapipe:tasks-genai:0.10.35
```

The local agent inspected the actual artifact/API rather than relying solely on assumptions.

The native library inside the AAR was confirmed to contain LiteRT-LM symbols/runtime, including things such as:

```text
litert_lm::Constraint
LlmLiteRTExecutor::AdvanceConstraintState
```

Therefore the MediaPipe artifact is not merely a generic old TFLite path; it contains the newer LiteRT-LM runtime.

## Relevant Java API findings

The inspected 0.10.35 API includes:

### LlmInference

Provides capabilities around:

- Creating inference
- Synchronous/asynchronous generation
- Token counting
- Closing the inference object

### LlmInferenceSession

Provides:

- `addQueryChunk`
- `addImage`
- `addAudio`
- `generateResponse`
- asynchronous generation
- cancellation
- session cloning
- session option updates

This makes the session API relevant to Loki's future persistent conversation architecture.

### Generation parameters

The API supports per-session/updateable parameters including:

- topK
- topP
- temperature
- random seed

### PromptTemplates

The inspected API exposes:

- model prefix/suffix
- system prefix/suffix
- user-related template controls

This can be used to implement chat/system prompt formatting.

### Backend

The API exposes backend selection such as:

- DEFAULT
- CPU
- GPU

### LoRA

The API exposes LoRA-related options.

---

# 4. Structured Output / Grammar Decision

A critical discovery was:

```text
LlmInferenceSessionOptions.setConstraintHandle(long)
```

exists.

The native LiteRT-LM runtime clearly contains constraint functionality.

However, in the currently inspected MediaPipe `tasks-genai 0.10.35` Java API:

> There is no public Java API that creates a constraint handle from a PEG/GBNF grammar.

The JNI/native surface inspected also did not expose a straightforward public `nativeCreateConstraint` path.

Therefore:

## Current LiteRT-LM structured-output capability

LiteRT-LM currently should be treated as:

```text
PROMPT_LEVEL_STRUCTURED_OUTPUT
```

for Loki's Java implementation.

It should **NOT** be falsely advertised as:

```text
GRAMMAR_ENFORCED_STRUCTURED_OUTPUT
```

unless a concrete supported API is implemented later.

## llama.cpp capability

The existing llama.cpp path has GBNF/grammar infrastructure and can therefore provide:

```text
GRAMMAR_ENFORCED_STRUCTURED_OUTPUT
```

where supported.

## Critical architectural requirement

The engine must be **capability-driven**.

No engine may silently ignore a requested guarantee.

For example, if the agent asks for grammar-enforced structured output and the active runtime cannot enforce it:

1. The engine/runtime must report that capability is unavailable.
2. The agent layer must explicitly select a fallback strategy.
3. The user/developer/debug pipeline should be able to observe that fallback.
4. The system must not silently pretend grammar was enforced.

---

# 5. Tool Calling on LiteRT-LM

Because current LiteRT-LM Java usage cannot directly create PEG/GBNF constraints, tool calling should initially use:

```text
Prompt-level protocol
        ↓
Generate response
        ↓
Parse JSON
        ↓
Validate
        ↓
If invalid:
    corrective retry
        ↓
If still invalid:
    honest fallback
```

This is intentionally weaker than llama.cpp grammar enforcement, but it is acceptable because LiteRT-LM is the chosen primary runtime.

The system should make this difference explicit through engine capabilities.

For example:

```text
LiteRT-LM:
    PROMPT_LEVEL_STRUCTURED_OUTPUT

llama.cpp:
    GRAMMAR_ENFORCED_STRUCTURED_OUTPUT
```

The agent layer should own the structured-output policy.

A reasonable current policy:

### Grammar-enforced runtime

- Request structured output.
- Parse/validate.
- Failure should be treated as an exceptional condition because grammar should make invalid output highly unlikely.

### Prompt-level runtime

- Request JSON through system/tool instructions.
- Parse and validate.
- If invalid, perform **one corrective retry** with a stricter instruction.
- If still invalid, produce an **honest explicit fallback**, not a misleading normal response.

This prevents the current bad behavior where:

```text
Model says:
"Hello! 👋 What would you like today?"

ToolCallParser:
DirectResponse
```

and the assistant appears to have successfully handled an agent request when it actually failed to produce the expected tool call.

---

# 6. Current Observed Tool-Calling Bug

Device logs showed:

```text
Turn started -> source=TEXT
Tools configured: 9 available, 0 disabled
LLM raw output:
Hello! 👋
What would you like today? 😊

Parsed -> DirectResponse
```

No tool call occurred.

A retry such as:

```text
"Call anki"
```

also produced conversational filler instead of a tool call.

There were no corresponding `LokiLlamaBridge` logs during this behavior.

The important conclusion was:

```text
model.bin exists
      ↓
AppModule selected LiteRtLlmEngine
      ↓
LiteRT path was used
      ↓
grammar parameter was not actually enforced
      ↓
model generated free-form text
      ↓
parser treated it as DirectResponse
```

This is a major architectural issue because the failure was silent.

The future implementation must make this observable and explicit.

---

# 7. Existing Model Situation

A 1.3 GB model exists on the test device.

The file was observed as:

```text
gemma-2b-it-cpu-int4.bin
```

and was copied into the current application's model location as:

```text
/sdcard/Android/data/dev.loki.android/files/model.bin
```

The current application package is:

```text
dev.loki.android
```

A previous package/path was:

```text
com.example.loki
```

The old internal path:

```text
/data/data/com.example.loki/files/
```

was not accessible/does not currently exist on the device.

There is also a shared-storage copy:

```text
/storage/emulated/0/gemma-2b-it-cpu-int4.bin
```

The exact model identity should NOT be inferred purely from the filename as a universal rule.

The filename strongly suggests the current test artifact is a Gemma 2B instruction/int4 CPU model, but model import architecture should validate model format/runtime compatibility rather than hard-coding Gemma assumptions.

---

# 8. Model Management Product Requirement

The app must eventually support a **model library**, rather than relying on one magic file such as:

```text
files/model.bin
```

The user explicitly wants to be able to:

- Download models
- Import models already stored locally
- Switch between models
- Eject/unload the currently loaded model
- Load another downloaded model
- Delete models separately

## Important meaning of "eject"

"Eject" DOES NOT mean delete.

Eject means:

> Unload the currently loaded model from memory/runtime so another model can be loaded.

The model files remain on disk.

Lifecycle concept:

```text
NOT_DOWNLOADED
      ↓
DOWNLOADED
      ⇅
LOADED
      ↓
EJECTED / DOWNLOADED
```

Deletion is separate:

```text
DOWNLOADED
      ↓
DELETE
      ↓
model files removed + registry entry removed
```

A loaded model should therefore never be deleted implicitly just because it was ejected.

---

# 9. Model-Centric Architecture

The architecture should be model-centric rather than file-existence-centric.

Do NOT use:

```text
if model.bin exists:
    automatically use LiteRT
else:
    use llama.cpp
```

as the long-term model-selection mechanism.

Instead, maintain model records.

Conceptually:

```text
ModelRecord
    id
    displayName
    family
    runtime
    format
    localPath
    sizeBytes
    source
    sourceUrl
    sha256/checksum
    state
    capabilities
    createdAt/importedAt
    lastUsedAt
```

The exact schema can be adjusted based on the existing codebase.

The active model should be represented by model identity/ID, not simply a hard-coded path.

Example conceptual state:

```text
Model Library

✓ Qwen ...
  LiteRT-LM
  1.8 GB
  LOADED

  Gemma ...
  LiteRT-LM
  1.3 GB
  DOWNLOADED

  Some GGUF model
  llama.cpp
  2.1 GB
  DOWNLOADED
```

The registry should support multiple models simultaneously.

---

# 10. Model Runtime / Format Identification

Model identity should include at least:

```text
runtime
format
family
```

The import pipeline should conceptually be:

```text
User selects model
       ↓
Detect format
       ↓
Determine compatible runtime(s)
       ↓
Validate model
       ↓
Collect/confirm metadata
       ↓
Register model
```

Potential formats include:

- LiteRT-LM model artifacts
- GGUF

Do not assume `.bin` universally means one specific model family.

Validation should be concrete and runtime-aware.

If runtime-specific validation requires attempting to initialize/load the model, that should happen in a controlled way and be cleaned up afterward.

---

# 11. Local Model Import Requirement

There must be a dedicated page where the user can import a locally available model.

Use Android's Storage Access Framework (SAF) rather than assuming arbitrary filesystem access.

Desired flow:

```text
Model Library
    ↓
Import Model
    ↓
Android file picker
    ↓
User selects model
    ↓
Copy into Loki-managed model directory
    ↓
Validate
    ↓
Collect/confirm metadata
    ↓
Register
    ↓
Model appears in library
```

The original user-selected file should not necessarily remain the canonical runtime file.

Loki should maintain its own managed model storage.

Conceptually:

```text
/storage/emulated/0/gemma-2b-it-cpu-int4.bin
            |
            | import
            v
Android/data/dev.loki.android/files/models/
            |
            +-- <model-id>/
                  +-- model artifact
                  +-- metadata
```

The exact storage layout should be chosen by the implementation based on Android constraints.

App-specific external storage is preferable where appropriate so arbitrary storage permissions are not required.

---

# 12. Hugging Face Download Requirement

There must be a dedicated model-library/download UI allowing the user to obtain a model from Hugging Face.

The user agreed with:

> **Small remote catalog with a bundled fallback.**

The intended architecture is:

```text
Remote curated catalog
        +
Bundled fallback catalog
```

The catalog should contain known-good model artifacts and metadata where possible.

However, the model manager should not necessarily be limited to only curated models if the product design allows user-supplied URLs.

The previous discussion considered:

- direct user-pasted artifact URL
- optional SHA-256
- streaming download
- progress
- temporary `.part` file
- final atomic rename
- resumable downloads using HTTP Range requests

These are implementation details to evaluate in the proposal.

A remote catalog should be small and updateable rather than embedding a huge hard-coded list into the app.

---

# 13. Network Stack

The current codebase inspection found no existing:

- OkHttp
- Ktor
- Retrofit

network stack.

Therefore a new lightweight HTTP client may be needed for model downloads.

OkHttp was considered a reasonable choice.

Do NOT introduce unnecessary networking dependencies beyond what model downloading actually requires.

---

# 14. Model Library Persistence Decision

Question 1 was:

> Room DB vs JSON manifest?

Decision:

> **Use a JSON manifest/file-based registry rather than Room.**

Reasoning:

- Loki is primarily a local assistant, not a data-heavy application.
- Model records are relatively small.
- A model registry does not justify a full database by itself.
- A JSON manifest is simpler and easier to inspect/debug.
- The registry can live alongside the managed model storage.

The implementation should still make the registry robust against partial writes/corruption where practical.

---

# 15. Model Identity / Import Metadata Decision

Question 2 was:

> What populates model family/name when importing a model?

Decision:

Use a combination of:

1. Format/runtime detection
2. Validation
3. Known metadata where available
4. User confirmation/input when identity cannot be reliably inferred

Do not fabricate metadata.

For known curated Hugging Face entries, catalog metadata can supply:

- display name
- family
- runtime
- format
- source URL
- checksum
- expected size

For arbitrary local files, runtime/format should be detected/validated and user-provided metadata may be required.

The filename may be used as a hint, not as authoritative truth.

---

# 16. Model Download Catalog Decision

Question 3 was:

> Should there be a curated model picker or just arbitrary URLs?

Decision:

> **Small remote catalog with a bundled fallback.**

The app should have a curated list of known-good model artifacts.

Remote catalog allows updating supported models without requiring an app release.

Bundled fallback means the app remains usable if:

- network is unavailable
- catalog endpoint is unavailable
- the remote catalog cannot be fetched

Exact catalog schema should be designed in the OpenSpec.

---

# 17. Existing 1.3 GB Model Migration

Question 4 was:

> What should happen to the existing model.bin?

Decision:

The existing model should **not become an orphan**.

It should be adopted/migrated into the new model library.

Known legacy locations should be considered where technically accessible, such as:

```text
app internal files/model.bin
app external files/model.bin
app external files/model.gguf
```

Potential shared-storage copies can be imported through SAF.

The path:

```text
/data/data/com.example.loki/...
```

belongs to a different application/package and should NOT be assumed accessible.

The migration logic must respect Android sandboxing.

The existing:

```text
/storage/emulated/0/gemma-2b-it-cpu-int4.bin
```

can be offered as/imported through SAF if appropriate.

---

# 18. OpenSpec Scope Decision

Question 5 was:

> One huge OpenSpec change or multiple changes?

Decision:

> **Split the work into multiple coherent OpenSpec changes.**

The preferred dependency order is:

## Change 1 — Model Library

Includes:

- model registry
- lifecycle
- import
- download
- model validation
- migration
- active model selection
- eject/load
- delete
- runtime/format metadata
- curated model catalog

## Change 2 — Engine Capabilities

Includes:

- session-based engine redesign
- LiteRT-LM primary runtime
- llama.cpp secondary runtime
- capability declaration
- no-silent-ignore behavior
- structured-output policy
- prompt-level JSON validation/retry
- cancellation
- persistent conversation sessions

## Change 3 — Agent Playground

Includes:

- editable system prompt
- tool instructions
- enabled/disabled tools
- generation parameters
- model selection
- debugging/observability
- pipeline visibility
- testing prompts

## Change 4 — Chat / Voice Experience Fixes

Includes:

- keyboard/IME UI issue
- tool semantics
- `call_contact` improvements
- tool descriptions
- voice/chat behavioral distinctions
- related UX fixes

Do NOT combine all four into one enormous change unless a concrete dependency requires it.

---

# 19. Agent Configuration / Playground Requirement

The app must have a dedicated page where the user can experiment with and modify the agent configuration.

The user specifically wants to be able to:

- Modify system prompt
- Experiment with system prompt
- Provide tool instructions
- Configure tools
- Test model behavior
- Observe what the agent is doing

The eventual Agent Playground should expose enough information to debug local model behavior rather than hiding everything behind the chat UI.

Conceptually:

```text
Agent Playground

Model
System Prompt
Tool Instructions
Enabled Tools
Generation Parameters

Test Prompt

Pipeline:
Input
↓
Prompt/session
↓
Raw model output
↓
Parse
↓
Validation
↓
Retry if needed
↓
Tool Call / Direct Response
```

The playground should make it possible to see where an agent turn failed.

---

# 20. Agent Runtime Architecture

The current `LlmEngine.generate(prompt, ...)` approach is effectively stateless per turn.

The agent discovered that this causes the entire conversation to be re-prefilled every turn.

LiteRT-LM's `LlmInferenceSession` is better suited to persistent conversational state.

Desired conceptual interface:

```text
LlmEngine
    ↓
openSession(SessionConfig)
    ↓
LlmSession
    ├── appendUser(text)
    ├── appendAssistant(text)
    ├── generate(GenerationParams, onToken)
    ├── cancel()
    └── close()
```

This should be considered for Change 2.

The session model can allow LiteRT-LM to maintain useful KV-cache/state and avoid repeatedly rebuilding the entire conversation prompt.

This is both an architectural improvement and a performance optimization.

---

# 21. Voice vs Chat Memory Requirement

This is a product-level requirement that should eventually be represented in the Agent Runtime.

## Voice mode

Voice mode is intended to behave like an assistant.

It should prioritize:

- Current request
- Current conversation/session context
- Tools
- Device actions
- Speed
- Low latency

It should **not automatically inject long-term memory** simply because the user has memory stored.

## Chat mode

Chat mode is intended to behave more like a conversational AI.

It should:

- Use conversation memory
- Maintain conversational context
- Retrieve relevant long-term memory when appropriate
- Support general conversation

The architecture should allow the same model/runtime to serve both modes while giving the Agent Runtime a mode/policy that determines memory behavior.

---

# 22. Tool Design Issues Already Identified

One existing tool issue:

`call_contact` currently expects a phone number.

The user wants assistant behavior such as:

```text
"Call Anki"
```

Therefore tool semantics should eventually support a name/contact identifier rather than forcing a raw phone number in one step.

Also, tool descriptions must not overclaim capabilities.

An `open_app` description should not casually imply support for arbitrary application names unless the implementation actually resolves them.

These belong primarily in Change 4 / agent/tool semantics.

---

# 23. Cancellation

The MediaPipe API provides:

```text
cancelGenerateResponseAsync
```

The current engine's cancellation was observed to be effectively a no-op.

Therefore Change 2 should make cancellation real and observable.

For a voice assistant, cancellation is particularly important because the user may interrupt the assistant.

---

# 24. Streaming Output Caveat

The local agent flagged a possible issue around MediaPipe `ProgressListener` semantics.

The current implementation appears to append `partialResult` into a buffer and also forward it as a token.

There is uncertainty about whether the version being used provides cumulative partial output or deltas.

Observed logs showed clean final output, which does not obviously match a naïve cumulative-append interpretation.

Therefore:

> Do not assume this bug is real without a targeted test.

Add a verification test during the engine redesign.

Do not introduce a speculative fix solely based on this observation.

---

# 25. UI Navigation

The codebase currently uses a simple `AppScreen` enum-style screen switching.

There is no established navigation framework.

Decision:

> **Do not introduce a navigation framework merely to support the model library/playground.**

The new pages can be integrated into the existing screen-switching architecture unless the codebase provides a concrete reason that a navigation framework is required.

Avoid unnecessary dependency/scope expansion.

---

# 26. Model Lifecycle Semantics

The model manager should clearly distinguish:

### NOT_DOWNLOADED

No local model artifact exists.

### DOWNLOADED

Model exists locally but is not currently loaded into the runtime.

### LOADED

Model is currently loaded and is the active inference model.

### EJECTED

"Ejected" is functionally an unloaded/downloaded state. The model file remains intact.

It may be represented as:

```text
DOWNLOADED + not active
```

rather than necessarily being a separate persisted state.

### DELETE

Delete means:

- Remove model artifact
- Remove associated model metadata/registry entry
- Release runtime if necessary before deletion

Deletion must not happen as a side effect of switching/ejecting.

---

# 27. Model Switching

Switching should conceptually be:

```text
Current model LOADED
        ↓
Eject / close current runtime
        ↓
Current model DOWNLOADED
        ↓
Load selected model
        ↓
Selected model LOADED
        ↓
Active model updated
```

The implementation must prevent two huge models from unnecessarily remaining loaded simultaneously.

Error handling should ensure that if loading the new model fails, the old model state and active-model metadata remain consistent.

---

# 28. Storage and Large Files

Models can be approximately 1 GB or larger.

Therefore download/import must be designed for large files.

Important considerations:

- Streaming rather than loading entire model into RAM
- Progress reporting
- Temporary `.part` file
- Atomic finalization/rename
- Optional checksum verification
- Handling interrupted downloads
- Potential resumable downloads
- Sufficient free-space checking where practical
- Safe deletion
- Runtime load failures
- No accidental duplication of multi-GB files unless necessary

Do not use an implementation that reads the entire model into memory.

---

# 29. Hugging Face / Download Security & Integrity

Where model downloads are supported, the architecture should allow:

```text
URL
size
SHA-256/checksum
```

and potentially other metadata.

For curated catalog entries, checksum verification is strongly preferred.

A download should ideally be:

```text
download → .part → verify → rename/register
```

not:

```text
download directly into active model path
```

A failed/incomplete download should not appear as a valid model.

---

# 30. Runtime Capability Model

The architecture should have an explicit capability concept.

Example:

```text
LiteRtLlmEngine
    PRIMARY
    PERSISTENT_SESSION
    CANCELLATION
    GENERATION_PARAMS
    PROMPT_LEVEL_STRUCTURED_OUTPUT

LlamaCppLlmEngine
    SECONDARY
    CANCELLATION
    GRAMMAR_ENFORCED_STRUCTURED_OUTPUT
```

Capabilities should evolve without forcing the rest of the app to know implementation-specific details.

Future MediaPipe/LiteRT-LM versions may expose better structured-output APIs.

The architecture should allow:

```text
future LiteRT-LM
    → GRAMMAR_ENFORCED_STRUCTURED_OUTPUT
```

without redesigning the entire agent system.

---

# 31. No Silent Capability Downgrade

This is a very important requirement.

If code requests:

```text
grammar-enforced structured output
```

but the active engine only supports:

```text
prompt-level structured output
```

the system must not silently ignore the grammar request.

Instead:

```text
Requested capability
        ↓
Engine capability check
        ↓
Supported?
   /         \
 yes          no
 |             |
 enforce       explicit fallback
```

The Agent Runtime should choose the fallback policy.

The Playground should expose enough information to understand which mode was actually used.

---

# 32. Current Primary Runtime Selection

Long-term runtime selection should be based on the **registered model's runtime/format identity**, not simply filename existence.

Example:

```text
ModelRecord
    runtime = LITERT_LM
    format = LITERT_MODEL
```

→ `LiteRtLlmEngine`

or:

```text
ModelRecord
    runtime = LLAMA_CPP
    format = GGUF
```

→ `LlamaCppLlmEngine`

This makes future model switching possible.

It also allows a model library to contain both LiteRT-LM and GGUF models.

---

# 33. Potential Model Support Beyond Gemma

A major user requirement is that the architecture must not become Gemma-specific.

The user explicitly asked whether a future LiteRT-LM model such as Qwen could be supported.

The conclusion is:

- LiteRT-LM is the runtime, not synonymous with Gemma.
- Model support depends on whether the model has a compatible LiteRT-LM artifact/model format and whether the MediaPipe/LiteRT-LM runtime supports that model architecture.
- The model library must therefore represent **model family and runtime compatibility explicitly**.
- Do not hard-code the model manager or engine around Gemma.

A future supported model could be something like Qwen if an appropriate LiteRT-LM-compatible artifact/runtime path exists.

Model metadata should therefore include family/model identity rather than assuming:

```text
runtime = LiteRT-LM
family = Gemma
```

for every `.bin`.

---

# 34. Agent Playground / Debug Observability

Because local LLMs may fail in ways that look like normal conversation, debugging visibility is important.

The pipeline should ideally expose stages such as:

```text
Input
↓
Agent configuration
↓
Prompt/session construction
↓
Raw model output
↓
Parser
↓
Validation
↓
Retry/correction
↓
Tool call OR direct response
```

The developer/user should be able to tell:

- Which model was active
- Which runtime was selected
- Which capabilities were available
- Whether structured output was grammar-enforced or prompt-level
- Whether a retry occurred
- Why a tool call was rejected
- What raw output was received

This is especially important because the current problem was a **silent structured-output failure**.

---

# 35. What the Next Agent Must NOT Assume

Do NOT assume:

1. LiteRT-LM must be replaced by llama.cpp.
2. LiteRT-LM is only for Gemma.
3. `.bin` always means Gemma.
4. A model file existing means it should automatically become active.
5. Eject means delete.
6. Voice and chat should have identical memory behavior.
7. All models support identical capabilities.
8. All structured output can be grammar-enforced.
9. A new navigation framework is required.
10. Room is required for model metadata.
11. The old `com.example.loki` sandbox can be accessed by `dev.loki.android`.
12. A speculative streaming bug should be fixed without a test.
13. The entire architecture should be redesigned in Change 1.

---

# 36. Current OpenSpec Change Boundaries

## Change 1 — model-library

Focus:

- JSON model registry
- model record
- model lifecycle
- managed storage
- local SAF import
- Hugging Face catalog/download
- bundled catalog fallback
- download progress
- integrity validation
- model validation
- migration/adoption of existing model
- active model
- load/eject/switch
- delete
- runtime/format identification
- model library UI
- necessary model-management navigation integration

Avoid deep agent/tool redesign here.

---

## Change 2 — engine-capabilities

Focus:

- LiteRT-LM as primary engine
- llama.cpp as secondary engine
- capability abstraction
- persistent `LlmInferenceSession` where appropriate
- session lifecycle
- cancellation
- generation parameters
- structured-output capability policy
- prompt-level JSON validation/retry
- grammar enforcement for llama.cpp
- explicit capability downgrade/fallback
- streaming correctness verification

---

## Change 3 — agent-playground

Focus:

- system prompt editor
- tool instructions
- enabled tools
- generation settings
- model selection
- test prompt
- pipeline visualization/debugging
- raw model output
- parser/validation/retry visibility
- capability visibility

---

## Change 4 — chat-experience-fixes

Focus:

- IME/keyboard layout issue
- voice/chat mode distinction
- memory policy by mode
- contact-name tool behavior
- tool descriptions
- other chat/voice UX issues

---

# 37. Expected Architecture Direction

The intended high-level architecture is:

```text
                         Loki Assistant
                              │
                ┌─────────────┴─────────────┐
                │                           │
             Voice Mode                  Chat Mode
                │                           │
       Assistant/action policy       Conversation/memory policy
                │                           │
                └─────────────┬─────────────┘
                              │
                        Agent Runtime
                              │
                    ┌─────────┴─────────┐
                    │                   │
               AgentConfig          Tools
                    │                   │
                    └─────────┬─────────┘
                              │
                        Active Model
                              │
                       Model Manager
                              │
                 ┌────────────┴────────────┐
                 │                         │
             LiteRT-LM                 llama.cpp
              PRIMARY                  SECONDARY
                 │                         │
        LiteRtLlmEngine             LlamaCppLlmEngine
                 │                         │
        LiteRT-LM model                GGUF model
```

The Model Manager should be responsible for **which model is available/active**, while the Engine layer is responsible for **how that model runs**, and the Agent Runtime is responsible for **how the model behaves as an assistant**.

Do not collapse these responsibilities.

---

# 38. Immediate Goal

The immediate goal is NOT to implement the entire assistant architecture.

The immediate goal is:

> Produce a high-quality OpenSpec proposal for **Change 1 — Model Library**, based on all decisions above.

The proposal should be grounded in the actual repository/codebase.

Before writing implementation tasks, the agent should inspect:

- existing module structure
- existing model manager
- current model path logic
- existing dependency versions
- existing UI screen architecture
- existing storage helpers
- existing permissions
- existing LLM engine interfaces
- current build configuration

It should not invent files/classes that already exist.

It should clearly distinguish:

- facts verified from the repository
- architectural decisions already made
- assumptions requiring implementation verification
- genuinely unresolved questions

---

# 39. Anti-Hallucination Instruction for the Next Agent

The previous local agent has a tendency to hallucinate or overstate findings.

Therefore:

## Evidence rules

When claiming something about the repository:

- Inspect the actual file.
- Quote/reference the actual class/function/path internally.
- Do not infer an API exists merely because it is common in another version.
- Do not claim a MediaPipe capability without verifying the exact dependency version/API.
- Do not claim a file exists without checking.
- Do not claim a migration path is possible if Android sandboxing prevents it.

## Decision rules

Do not reopen already-settled decisions unless new repository evidence makes them technically impossible.

Settled decisions include:

- LiteRT-LM primary
- llama.cpp secondary
- model library
- JSON registry
- small remote catalog + bundled fallback
- SAF local import
- separate eject vs delete
- multiple OpenSpec changes
- capability-driven engines
- voice/chat memory distinction
- no unnecessary navigation framework

## Proposal rules

The proposal should not silently add:

- unrelated dependencies
- unrelated features
- a navigation framework
- database infrastructure
- complete agent redesign into Change 1

If something is genuinely necessary for Change 1, explain why.

---

# 40. Final Decisions Summary

| Topic | Decision |
|---|---|
| Product identity | Local Android assistant with hybrid chat/voice behavior |
| Primary runtime | LiteRT-LM |
| Secondary runtime | llama.cpp |
| LiteRT structured output today | Prompt-level JSON + validation/retry |
| llama.cpp structured output | GBNF/grammar enforcement |
| Engine architecture | Capability-driven |
| Silent capability downgrade | Not allowed |
| Model management | Dedicated model library |
| Model switching | Supported |
| Eject | Unload model, do NOT delete |
| Delete | Remove model and registry entry |
| Local model import | Android SAF |
| HF models | Dedicated download flow |
| Model catalog | Small remote catalog + bundled fallback |
| Model registry | JSON manifest/file registry |
| Model identity | Runtime/format detection + validation + metadata/user confirmation |
| Existing model | Migrate/adopt into model library |
| Model assumptions | Do not hard-code Gemma |
| Runtime selection | Based on model identity/runtime/format |
| Navigation | Keep existing simple screen routing; no framework solely for this |
| Engine redesign | Separate Change 2 |
| Agent playground | Separate Change 3 |
| Chat/voice fixes | Separate Change 4 |
| Voice memory | Do not automatically use long-term memory |
| Chat memory | Use memory/context |
| Tool failure behavior | Validate, one corrective retry, honest fallback |
| Cancellation | Must become real in engine redesign |
| Large files | Stream; avoid loading entire model into RAM |
| Downloads | Temporary file + verification + atomic finalization |
| Debugging | Observable agent pipeline |
| Next task | Draft OpenSpec Change 1 — Model Library |

---

# 41. Instruction to the OpenSpec Agent

Using this document as the authoritative product/architecture context:

1. Inspect the actual repository.
2. Verify existing code and dependencies.
3. Do not hallucinate repository/API details.
4. Do not reopen settled decisions without evidence.
5. Produce an OpenSpec proposal for **Change 1 — Model Library**.
6. Keep the proposal scoped to model management and its required UI/storage infrastructure.
7. Explicitly document any assumptions that cannot be verified.
8. Preserve compatibility with the future Change 2 engine-capability architecture.
9. Design the model registry so multiple LiteRT-LM and GGUF models can coexist.
10. Treat LiteRT-LM as the primary runtime but do not make the model library Gemma-specific.
11. Preserve the distinction between **eject/unload** and **delete**.
12. Preserve the distinction between **voice assistant memory policy** and **chat memory policy**, even if those behaviors are implemented in later changes.
13. Do not introduce a navigation framework unless repository evidence proves it is necessary.
14. Do not introduce Room solely for the model registry.
15. Do not redesign the entire LLM engine in Change 1.

The end result should be a concrete, repository-grounded OpenSpec proposal that can later be implemented incrementally without locking Loki into the current single-model `model.bin` architecture.
