# Design: add-npu-backend-support

## Context

- Loki runs litertlm-android **0.16.1**; `Backend.NPU(nativeLibraryDir)` exists in its Kotlin API
  but is never constructed. QNN/QAIRT runtime libraries are absent, so the native runtime logs
  `NPU accelerator could not be loaded` and NPU-compiled models fail with `Section not found`.
- Qualcomm publishes the QNN runtime officially on Maven Central
  (`com.qualcomm.qti:qnn-runtime`, versions 2.26.0→2.49.0, license "Qualcomm AI Hub Model
  License", NOTICE.txt inside the AAR). This is the sanctioned sourcing channel — Loki must not
  commit, mirror, or host Qualcomm binaries itself.
- Field-proven reference implementations: AndroLLM (engine patterns, minimal AOT bundle,
  `useLegacyPackaging` requirement) and Google AI Edge Gallery (`Backend.NPU` construction,
  sampler skip, `uses-native-library` manifest entries).
- Validation device: SM8750 (HTP v79 per `supported_soc.csv`) with
  `gemma-4-E2B-it_qualcomm_sm8750.litertlm`.

## Goals / Non-Goals

**Goals**
- NPU execution of compatible `.litertlm` models on Snapdragon devices, gated by device
  capability AND model compatibility.
- AUTOMATIC as primary backend resolving NPU → GPU → CPU; transactional, observable attempts.
- Official Maven dependency as the only QNN sourcing channel; no Loki-hosted Qualcomm binaries.

**Non-Goals**
- Play Feature Delivery / AI Packs (Play distribution undecided).
- JIT (on-device) NPU compilation — AOT `.litertlm` models only; `libQnnHtpPrepare.so` not required.
- MediaTek / Samsung / Tensor NPU enablement (probe architecture supports them; enablement later).
- APK size optimization (trimming the AAR to ~25MB) — optimization later, full AAR initially.

## Decisions

### D1. QNN ↔ LiteRT-LM version pairing (Spike 1, resolves before implementation)
litertlm 0.16.1's native runtime was built against a specific QAIRT release. Google's
`third_party/qairt/workspace.bzl` (LiteRT repo) pins **2.42.0.251225** for the LiteRT revision
contemporary with litertlm 0.16.x; QAIRT publishes Maven versions in lockstep (2.42.0 exists).
**Candidate: `com.qualcomm.qti:qnn-runtime:2.42.0`.** Spike 1 confirms by (a) checking the
litertlm 0.16.1 POM/transitive deps and the LiteRT workspace.bzl at the matching revision, and
(b) an on-device init test on SM8750 — mismatch in either direction surfaces as dispatch-load
failures in logcat. The pinned version is recorded in `gradle/libs.versions.toml` and gates all
other NPU tasks.

### D2. Hardware capability detection is separate from model compatibility
- **Hardware probe** (`core:llm`, once per engine init, pure-detection core unit-testable):
  - `NpuVendor` detection from `Build.SOC_MANUFACTURER`/`SOC_MODEL` (+ hardware fallbacks):
    Qualcomm / MediaTek / Google Tensor / Samsung / Unknown.
  - HTP generation lookup from the pinned `supported_soc.csv` mapping (SM8750→v79 …).
  - `npuUsable`: QNN libraries + `libLiteRtDispatch_Qualcomm.so` actually present in
    `applicationInfo.nativeLibraryDir` (filesystem check, not an init attempt).
  - Exposed as `BackendCapabilities` via engine state — observable, never a decision by itself.
- **Model compatibility** (`core:models`/`ModelManager`): the record carries NPU target metadata
  (SoC / HTP generation) parsed from the `qualcomm_<soc>` artifact naming at import.
  Compatibility = probe generation/target matches the model's. The two concerns meet only in
  backend candidate selection.

### D3. Import marks incompatibility; never hard-rejects
A SoC-targeted NPU model **imports successfully on any device**. The record is flagged with
`npuTargetSoc`; the library UI shows **"Unavailable for execution on this device (<reason>)"**
when the current SoC does not match. The model stays importable/kept and becomes executable on
a matching device. Only structural failures (corrupt file, wrong format) reject import, as today.

### D4. Backend resolution and fallback — transactional & observable
- `ExecutionBackend` gains `NPU`. `AUTOMATIC` (default) resolves the candidate chain
  **NPU → GPU → CPU**, including NPU only when `npuUsable && modelCompatible`.
- **Transactional attempts**: each candidate runs inside an attempt that fully releases native
  resources on failure before the next starts (extends the existing `releaseNativeResources()`
  pattern). No partial state crosses attempt boundaries.
- **Observable**: each attempt is recorded (`backend`, `durationMs`, `outcome`, `failureReason`)
  into an `EngineInitReport` surfaced via `LlmModelState` and logs; the UI shows the active
  backend and, after fallback, why earlier candidates failed. NPU failures are never silent.
- **Explicit NPU is exclusive**: a manual NPU selection builds a single-candidate chain —
  failure surfaces to the user; no silent swap. NPU is an **advanced** setting, not a standard
  Agent Playground radio option.
- NPU active ⇒ `ConversationConfig.samplerConfig` is not customized (NPU-compiled models reject
  sampler customization — pattern proven in Google AI Edge Gallery).

### D5. Sourcing & packaging
- `com.qualcomm.qti:qnn-runtime:<pinned>` Gradle dependency (D1). Gradle extracts its jniLibs
  into the APK; nothing committed to Git or hosted by Loki.
- `libLiteRtDispatch_Qualcomm.so` built by CI from the LiteRT revision matching litertlm 0.16.1
  (`@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so`), staged via Gradle, not committed.
- `packaging { jniLibs { useLegacyPackaging = true } }` — the dispatch locates vendor libraries
  via `readdir()` of `nativeLibraryDir`; without it the dir is empty and NPU can never
  initialize (verified in AndroLLM).
- Manifest: `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`.
- License/NOTICE from the AAR retained in the APK; release gate verifies the pinned version's
  license text explicitly permits object-code bundling inside the application.

### D6. Validation-first rollout (all on SM8750 + Qualcomm E2B model first)
1. Spike 1 (D1) version pairing on-device.
2. NPU init succeeds; generation produces tokens; sampler-skip path exercised.
3. Forced-failure matrix: generic GPU model under NPU attempt → clean GPU fallback with report;
   NPU model on non-matching SoC → marked unavailable, never attempted.
4. Only then: broader device matrix and UI polish.

### D7. Import validation strategy — structural, not live-execution (Gap fix)
The existing `LiteRtModelValidator` performs a **live** `Engine(modelPath).initialize()` with the
default (CPU/GPU) backend. For NPU-targeted models this throws `Section not found` and aborts
import before the engine or advisory marking is ever reached — and it would equally fail on
non-matching devices, contradicting D3. Therefore:
- `LitertLmContainerInspector` is extended: `Info` gains `npuTargetSoc: String?` and
  `isNpuTargeted: Boolean`, detected from the container header/metadata table (section names
  such as QNN/backend markers) with the `qualcomm_<soc>` filename convention as a fallback
  (so renamed files still detect where possible).
- For NPU-targeted artifacts, validation at import is **structural only**: container magic
  (`LITERTLM`), readable metadata table, and file readability — via
  `LitertLmContainerInspector`. No `Engine.initialize()` is attempted for NPU-targeted models
  (execution is proven at load time by the transactional engine chain instead).
- Generic GPU/CPU models keep the existing live-init validation unchanged.
- `LiteRtModelValidator` needs no `Context` under this design (structural checks are pure),
  avoiding an API change to the validator interface.

### D8. Local developer path for the dispatch library (Gap fix)
A fresh clone must build with `./gradlew assembleDebug` without Bazel/NDK/QNN toolchain setup.
- A `scripts/fetch_dispatch.sh` (documented in design) either downloads the matching prebuilt
  `libLiteRtDispatch_Qualcomm.so` from Loki's CI artifacts (pinned by SHA-256 to the litertlm
  revision) or documents the local bazel build command as fallback.
- A Gradle task verifies the staged dispatch `.so` exists when the QNN runtime dependency is
  present; if it is missing, the build **succeeds but logs a clear warning** and the hardware
  probe reports `npuUsable=false` (graceful degradation, matching the probe's filesystem-check
  design) — NPU is optional by construction, so its absence must never break a build.

### D9. R8/ProGuard keep rules (Gap fix)
Release builds run R8; LiteRT-LM and QNN resolve native libraries via `dlopen`/JNI name lookup.
`proguard-rules.pro` keeps `com.google.ai.edge.litertlm.**` and `com.qualcomm.**` classes with
native method entrypoints so obfuscation cannot break JNI binding.


## Risks / Trade-offs

- **QNN↔litertlm version mismatch** → Spike 1 gates all work; recorded pin.
- **Maven AAR size (~67MB)** → accepted initially; trimming (~25MB minimal AOT set) is a
  documented later optimization, not a prerequisite.
- **Model-specific NPU init failures at runtime** → transactional chain + repeated-failure
  backoff ensure AUTO always lands on a working backend.
- **License drift across QNN versions** → release-gate check tied to the pinned version.

## Migration Plan

1. Spike 1 (D1) — version pairing verified on device. Gate for everything else.
2. Dispatch `.so` build/stage; Maven dependency; legacy packaging; NOTICE in APK.
3. Probe + capabilities + backend chain in `core:llm` with unit tests (pure logic).
4. Import marking + model library UI states.
5. Advanced NPU setting UI + active-backend display.
6. SM8750 validation matrix (D6). No data migration — existing records unchanged; NPU metadata
   is additive at import.

## Open Questions

- Exact pinned QNN version (Spike 1 output; candidate 2.42.0).
- Whether `libQnnHtpPrepare.so` proves unnecessary for AOT on-device (expected; confirm during
  validation — the Maven AAR ships it anyway, so it only matters for future trimming).

