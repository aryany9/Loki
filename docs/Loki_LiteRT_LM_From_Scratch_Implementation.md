# Loki — LiteRT-LM-First LLM Architecture
## From-Scratch Implementation Specification

**Purpose:** This document is the single source of truth for implementing/rebuilding Loki's Android LLM layer from the current codebase state.

**Primary goal:** Make Loki a clean, Android-native local LLM application using **LiteRT-LM 0.13.1** as the sole inference runtime and **`.litertlm`** as the sole supported LLM model format.

---

# 1. Non-Negotiable Requirements

These requirements must not be violated.

1. **LiteRT-LM 0.13.1 is the only active inference runtime.**
2. **`.litertlm` is the only supported model format.**
3. Remove `llama.cpp` and GGUF support.
4. Remove `DelegatingLlmEngine` and runtime-selection abstractions.
5. **Java 21 must remain unchanged.**
6. Do **not** add `-Xskip-metadata-version-check` or any equivalent Kotlin compiler suppression.
7. Do not downgrade Java to 17.
8. Do not randomly upgrade/downgrade Kotlin to make the build pass.
9. Do not modify unrelated `core/**/build.gradle.kts` files.
10. Keep `litertlm-android` pinned to **0.13.1** for this implementation.
11. Remove MediaPipe GenAI only after repository-wide reference tracing confirms it is unused.
12. Preserve the existing model-management infrastructure where it is still useful: `ModelManager`, `ModelRegistry`, `ModelRecord`, metadata, import/download/unload functionality.
13. Do not introduce speculative abstractions such as:
    - `DelegatingLlmEngine`
    - `LlamaCppLlmEngine`
    - `RuntimeFactory`
    - `EngineFactory`
    - `RuntimeRegistry`
    - multi-runtime strategies
14. GPU → CPU fallback must only happen for genuine backend/device initialization failures.
15. Model/artifact failures must **not** silently trigger CPU fallback.
16. The implementation is not complete merely because Gradle compiles.
17. The exact Qwen3 `.litertlm` model must successfully initialize and generate a response on a real Android device.

---

# 2. Target Architecture

The desired architecture is deliberately small:

```text
                     ModelManager
                          │
                     ModelRegistry
                          │
                          ▼
                    .litertlm model
                          │
                          ▼
                      LlmEngine
                          │
                          ▼
                   LiteRtLlmEngine
                          │
                          ▼
                LiteRtLlmJavaHelper
                          │
                    LiteRT-LM 0.13.1
                          │
                  ┌───────┴───────┐
                  ▼               ▼
                 GPU             CPU
                  │               ▲
                  └── failure ────┘
```

Responsibilities:

### `LlmEngine`
Loki's application-level LLM interface.

### `LiteRtLlmEngine`
Kotlin-facing implementation of `LlmEngine`.

Responsibilities:
- engine lifecycle;
- conversation lifecycle;
- generation;
- cancellation where supported;
- streaming response delivery;
- exposing readiness/state to the rest of the app;
- delegating LiteRT-LM-specific Java API operations to the helper.

It should **not** contain duplicated low-level LiteRT-LM `Engine` construction logic.

### `LiteRtLlmJavaHelper`
Java bridge around LiteRT-LM 0.13.1.

Responsibilities:
- create/configure LiteRT-LM `Engine`;
- select backend;
- GPU → CPU fallback;
- model initialization/validation;
- LiteRT-LM-specific callbacks;
- native resource cleanup;
- expose only the operations needed by `LiteRtLlmEngine`.

### Model Management
`ModelManager`, `ModelRegistry`, `ModelRecord`, metadata, import/download/unload functionality should remain where useful.

The model-management layer should not contain runtime-selection logic for multiple inference engines.

---

# 3. Before Changing Anything: Inspect the Repository

Do not assume the repository matches previous descriptions.

Before editing, inspect:

```text
settings.gradle(.kts)
build.gradle(.kts)
gradle/libs.versions.toml
app/
core/
core/llm/
core/ui/
```

Identify the current implementations of:

```text
LlmEngine
LiteRtLlmEngine
LiteRtLlmJavaHelper
LlamaCppLlmEngine
DelegatingLlmEngine
ModelTypes
ModelManager
ModelRegistry
ModelRecord
AppModule
```

Also search the entire repository for:

```text
LlamaCppLlmEngine
DelegatingLlmEngine
ModelRuntime
ModelFormat
GGUF
BIN
litertlm
mediapipe
tasks-genai
llama
cmake
externalNativeBuild
kotlin-stdlib
```

Create a short internal current-state map before making destructive changes.

Do not delete a class until all references have been traced.

---

# 4. Dependency Investigation

## 4.1 LiteRT-LM

The project must use:

```text
litertlm-android:0.13.1
```

Do not change this version unless implementation is impossible and the reason is explicitly reported.

Verify the actual APIs from the resolved 0.13.1 dependency rather than relying on documentation for a different version.

---

## 4.2 MediaPipe

The project previously contained:

```text
com.google.mediapipe:tasks-genai:0.10.35
```

Search the whole repository for active usage.

If there is no active source/build dependency requiring it:

- remove the MediaPipe dependency;
- remove its version declaration if unused;
- remove MediaPipe-specific code/configuration if obsolete.

Do not reintroduce MediaPipe merely to solve a LiteRT-LM problem.

---

## 4.3 llama.cpp

Trace all llama.cpp dependencies and native build configuration.

Remove only obsolete components, including as applicable:

- llama.cpp libraries;
- native sources;
- CMake configuration;
- `externalNativeBuild`;
- JNI glue that exists solely for llama.cpp;
- Gradle configuration used solely by llama.cpp;
- `LlamaCppLlmEngine`;
- `DelegatingLlmEngine`;
- GGUF-specific runtime selection.

Do not remove shared native/build infrastructure that is still required by LiteRT-LM.

---

# 5. Kotlin/Java Build Problem

The current known compiler failure includes:

```text
kotlin-stdlib:2.2.21
binary version of its metadata is 2.2.0
expected version is 2.0.0
```

and:

```text
java.lang.IllegalArgumentException: source must not be null
```

inside Kotlin FIR analysis.

Treat the `source must not be null` error as potentially secondary to the Kotlin metadata/compiler mismatch until proven otherwise.

## Required investigation

Use Gradle dependency inspection, for example:

```bash
./gradlew :app:dependencyInsight --dependency kotlin-stdlib
```

and inspect relevant Kotlin plugin/compiler configuration.

Determine exactly:

1. Which Kotlin compiler/plugin version the project uses.
2. Which dependency introduces `kotlin-stdlib:2.2.21`.
3. Whether another dependency is forcing that version.
4. Whether dependency constraints/resolution are causing the mismatch.

## Rules

Do NOT:

```text
-Xskip-metadata-version-check
```

Do NOT downgrade Java.

Do NOT blindly upgrade the entire Kotlin toolchain to 2.2.x.

Do NOT blindly downgrade stdlib.

Do NOT change unrelated modules just to suppress the error.

Use the smallest evidence-based dependency alignment that makes the resolved Kotlin compiler and Kotlin libraries compatible.

Java 21 is a hard requirement.

---

# 6. Model Format

The new supported format is:

```text
.litertlm
```

GGUF and legacy `.bin` inference support are to be removed after reference tracing confirms they are obsolete.

Before deleting `.bin` support, trace:

```text
ModelFormat.BIN
.bin detection
.bin loading
TFLite magic/TFL3 detection
old model-loading paths
```

Confirm these paths are exclusively associated with the obsolete implementation.

Do not delete the model-management layer merely because `.bin` is being removed.

If `ModelFormat` remains useful to the model-management/UI/metadata layer, it may be retained with only:

```kotlin
LITERTLM
```

If the enum becomes unnecessary after the cleanup, simplify it instead of keeping a meaningless abstraction.

Do not create abstractions solely for hypothetical future formats.

---

# 7. Model Management

Preserve and adapt:

```text
ModelManager
ModelRegistry
ModelRecord
metadata
import
download
unload/release
model switching
```

The model-management layer should represent a `.litertlm` model and provide the path to the LLM engine.

It should not decide between multiple runtimes because Loki has only one runtime.

A model record should retain useful information such as:

```text
id
name
path
size
source
metadata
status
```

using the project's existing structure where appropriate.

Do not redesign unrelated model-management functionality.

---

# 8. LiteRtLlmJavaHelper

This component owns LiteRT-LM-specific engine construction.

## Initialization

The intended sequence is:

```text
initialize(modelPath)
       │
       ▼
try GPU backend
       │
       ├── success → READY
       │
       └── genuine backend/device failure
                    │
                    ▼
                try CPU
                    │
                    ├── success → READY
                    │
                    └── failure → report original/root cause
```

## Critical fallback rule

Do NOT classify all exceptions as GPU failures.

These are model/artifact failures and should NOT automatically trigger a CPU retry:

```text
SentencePiece tokenizer not found
Section not found
invalid model
corrupted model
unsupported model format
missing model metadata
malformed artifact
model-specific configuration failure
```

For example:

```text
Section not found
SentencePiece tokenizer is not found in the model
```

must not be converted into:

```text
GPU unavailable → CPU fallback
```

If LiteRT-LM 0.13.1 does not provide sufficiently granular exception types to reliably distinguish backend failures from model failures, do not invent unreliable classification logic.

In that case:

- preserve the original exception;
- preserve useful native log information;
- document the limitation;
- avoid hiding the actual model-loading failure.

## Validation

`validateModel` must perform a meaningful LiteRT-LM initialization/validation check rather than merely checking:

```text
file exists
extension == ".litertlm"
```

Validation should use the actual LiteRT-LM API available in 0.13.1.

Do not duplicate validation logic unnecessarily between the helper and engine.

---

# 9. LiteRtLlmEngine

Make this the sole `LlmEngine` implementation.

Responsibilities:

```text
initialize
generate
stream tokens
cancel where supported
release
isReady
prompt/system configuration if supported by current API
```

Do not add a runtime parameter to `initializeAsync`.

There is only one runtime.

Prefer an interface such as the project's existing equivalent:

```kotlin
suspend fun initializeAsync(modelPath: String? = null): Boolean
```

Adapt to the actual existing interface rather than blindly replacing unrelated APIs.

## Generation

Use the actual LiteRT-LM 0.13.1 conversation/generation APIs.

Ensure:

```text
user prompt
    ↓
LiteRT-LM conversation/session
    ↓
streaming callback
    ↓
LlmEngine callback/Flow/contract
    ↓
UI
```

Do not invent APIs from newer LiteRT-LM versions.

## Lifecycle

The expected lifecycle is:

```text
initialize
    ↓
generate
    ↓
generate
    ↓
release
```

`release()` should be idempotent.

After release, a later:

```text
initialize
```

must work correctly.

Native resources must not leak when:

- initialization fails;
- generation fails;
- generation is cancelled;
- release is called twice;
- model is reloaded.

---

# 10. Dependency Injection

`AppModule` should provide:

```text
LiteRtLlmEngine
```

directly as the `LlmEngine` implementation.

Remove:

```text
runtime selection
engine delegation
multiple engine injection
```

Do not introduce a factory merely to construct the one engine.

---

# 11. Delete Obsolete Architecture

After reference tracing and migration:

```text
LlamaCppLlmEngine.kt
DelegatingLlmEngine.kt
```

should be deleted.

Also remove obsolete:

```text
ModelRuntime
GGUF
legacy BIN runtime logic
llama.cpp configuration
```

where proven unused.

Do not leave dead runtime-selection code behind.

---

# 12. Qwen3 Model: Mandatory Acceptance Test

The exact model to test is:

```text
qwen3_4b_channelwise_int8_float32kv.litertlm
```

Source:

```text
https://huggingface.co/litert-community/Qwen3-4B
```

This model is the primary acceptance test for the implementation.

The following sequence must work on a real Android device:

```text
Import
  ↓
Detect .litertlm
  ↓
Model metadata/record creation
  ↓
LiteRT-LM initialization
  ↓
Conversation/session creation
  ↓
Prompt
  ↓
Streaming generation
  ↓
UI receives response
  ↓
Release
  ↓
Reload
  ↓
Generate again
```

Compilation is not sufficient.

---

# 13. Existing Qwen3 Failure Must Be Investigated

The known previous failure was:

```text
litert_lm_loader.cc:261
Section not found
```

followed by:

```text
SentencePiece tokenizer is not found in the model.
```

This must be investigated at the actual LiteRT-LM loader/model-artifact layer.

Possible causes must be determined from evidence, not guessed.

Inspect:

- actual downloaded file;
- file size;
- extension;
- model artifact structure where possible;
- LiteRT-LM 0.13.1 loader expectations;
- tokenizer packaging;
- model metadata;
- exact initialization API;
- native logcat output.

Do not "fix" this by:

- changing `ChatScreen.kt`;
- changing Java 21 to 17;
- adding Kotlin compiler suppressions;
- adding unrelated dependencies;
- downgrading/upgrade random dependencies;
- switching back to MediaPipe without evidence.

If the Qwen3 artifact is incompatible with the exact LiteRT-LM 0.13.1 Android API, report that clearly instead of creating a workaround that hides the incompatibility.

---

# 14. Testing

## Unit tests

Test at minimum:

### Initialization

```text
successful initialization
already initialized behavior
failed initialization
```

### Backend fallback

Test:

```text
GPU success → GPU remains active
GPU backend failure → CPU attempted
GPU success → CPU not attempted
model error → CPU not attempted
```

### Generation

Test:

```text
prompt accepted
streaming tokens delivered
generation completion
generation error
cancellation where supported
```

### Lifecycle

Test:

```text
release
release twice
initialize after release
failed initialization cleanup
```

Use mocks/fakes where necessary.

Do not create tests that only assert implementation details unrelated to the public engine behavior.

---

# 15. Build Verification

Run focused builds incrementally.

At minimum:

```bash
./gradlew :core:llm:assembleDebug
./gradlew :core:llm:test
```

Also build the app after the LLM module is stable.

If the project uses other relevant verification tasks, run those as appropriate.

Do not make a large number of unrelated changes and only run Gradle at the end.

---

# 16. Manual Android Verification

Use a real Android device.

Verify:

### Model import

```text
qwen3_4b_channelwise_int8_float32kv.litertlm
```

is imported successfully.

### Detection

It is recognized as:

```text
LITERTLM
```

and not as GGUF/BIN.

### Initialization

LiteRT-LM 0.13.1 initializes the model.

### Backend

Observe logcat to determine whether:

```text
GPU
```

was used.

If GPU initialization genuinely fails, verify:

```text
CPU fallback
```

occurs.

### Generation

Send a real prompt and verify streaming output.

### Lifecycle

Release the model, reload it, and generate again.

### Error behavior

If initialization fails, the UI/logs must retain a useful root-cause error.

---

# 17. What NOT To Do

Do not:

- add GGUF support;
- add llama.cpp back;
- add DelegatingLlmEngine;
- add runtime selection;
- add a runtime factory;
- add speculative architecture;
- downgrade Java;
- add `-Xskip-metadata-version-check`;
- randomly modify Kotlin versions;
- randomly modify Gradle files;
- reintroduce MediaPipe without evidence;
- catch all exceptions and interpret them as GPU failures;
- hide the Qwen3 tokenizer/loader error;
- change UI code to compensate for build-system problems;
- claim success because APK compilation succeeds.

---

# 18. Implementation Order

Follow this order.

## Phase 1 — Repository inspection

Trace:

```text
runtime
model format
engine
dependencies
native build
DI
tests
```

Do not edit yet.

## Phase 2 — Build/dependency diagnosis

Resolve:

```text
Kotlin compiler ↔ kotlin-stdlib mismatch
```

while preserving:

```text
Java 21
LiteRT-LM 0.13.1
```

## Phase 3 — Remove obsolete runtime

After tracing references:

```text
llama.cpp
GGUF
DelegatingLlmEngine
LlamaCppLlmEngine
```

## Phase 4 — Establish LiteRT-LM path

Implement:

```text
LlmEngine
    ↓
LiteRtLlmEngine
    ↓
LiteRtLlmJavaHelper
    ↓
LiteRT-LM 0.13.1
```

## Phase 5 — Backend handling

Implement:

```text
GPU → CPU fallback
```

with correct error classification.

## Phase 6 — Model management

Ensure:

```text
ModelManager
ModelRegistry
ModelRecord
```

correctly manage `.litertlm`.

## Phase 7 — Qwen3 investigation

Test the actual artifact and resolve the loader/tokenizer problem.

## Phase 8 — Generation/lifecycle

Verify:

```text
streaming
release
reload
```

## Phase 9 — Full verification

Run tests and real-device validation.

---

# 19. Definition of Done

The task is complete only when all of the following are true:

- [ ] Java 21 remains configured.
- [ ] No Kotlin metadata suppression exists.
- [ ] Kotlin compiler/dependency versions are properly aligned.
- [ ] LiteRT-LM remains pinned to 0.13.1.
- [ ] `.litertlm` is the supported model format.
- [ ] GGUF support is removed.
- [ ] llama.cpp is removed.
- [ ] `DelegatingLlmEngine` is removed.
- [ ] `LlamaCppLlmEngine` is removed.
- [ ] No obsolete runtime-selection logic remains.
- [ ] MediaPipe GenAI is removed if repository tracing proves it unused.
- [ ] `LiteRtLlmEngine` is the sole `LlmEngine` implementation.
- [ ] `LiteRtLlmJavaHelper` owns LiteRT-LM Engine construction.
- [ ] GPU → CPU fallback works for genuine backend failures.
- [ ] Model/artifact errors do not trigger misleading fallback.
- [ ] Resource lifecycle is safe and idempotent.
- [ ] Model management continues to work.
- [ ] Unit tests pass.
- [ ] `:core:llm:assembleDebug` passes.
- [ ] The Qwen3 `.litertlm` model imports successfully.
- [ ] The Qwen3 model initializes successfully on a real Android device.
- [ ] Qwen3 produces streaming output.
- [ ] Qwen3 can be released and reloaded.
- [ ] No unrelated Gradle/build changes were introduced.

---

# 20. Required Final Report

When finished, provide:

## Architecture

Explain the final LLM architecture and why each remaining component exists.

## Files

List:

```text
Added
Modified
Deleted
```

## Dependencies

Report:

```text
LiteRT-LM version
MediaPipe status
llama.cpp status
Kotlin compiler version
kotlin-stdlib version
Java version
```

## LiteRT-LM API

List the exact APIs from **0.13.1** that were used.

Do not report APIs from another version.

## GPU/CPU

Explain exactly:

```text
when GPU is attempted
what constitutes GPU failure
when CPU fallback happens
when fallback does NOT happen
```

## Qwen3

Report the actual result of:

```text
qwen3_4b_channelwise_int8_float32kv.litertlm
```

Include relevant logcat evidence.

If it fails, report:

```text
exact failure
exact API call
backend attempted
model path
LiteRT-LM version
relevant native logcat
probable root cause
```

Do not claim completion if this acceptance test fails.

## Tests

Report exact commands executed and whether they passed.

---

# 21. Agent Behavior Rule

This project has previously suffered from hallucinated fixes and unrelated build changes.

Therefore:

**When uncertain, inspect first.**

**When an API is uncertain, verify it against the actual resolved dependency.**

**When a build error is unrelated to the feature, do not modify feature code to hide it.**

**When a model fails to load, investigate the model/runtime boundary instead of changing unrelated Gradle or UI code.**

**Prefer the smallest correct change over a broad refactor.**

**Never introduce an abstraction merely because it might be useful in the future.**

The goal is not to produce the largest implementation.

The goal is to produce the **smallest correct, maintainable LiteRT-LM-first architecture that can actually run the Qwen3 `.litertlm` model on Android.**
