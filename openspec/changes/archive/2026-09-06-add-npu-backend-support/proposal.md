# Proposal: add-npu-backend-support

## Why

Loki cannot execute NPU-compiled `.litertlm` models (e.g. `gemma-4-E2B-it_qualcomm_sm8750.litertlm`).
The LiteRT-LM runtime fails to register the NPU accelerator at startup
(`npu_registry.cc: NPU accelerator could not be loaded`) because no QNN/QAIRT runtime libraries
are packaged, so NPU-compiled models fail import/validation with a cryptic `Section not found`.
There is also no device-vs-model compatibility awareness, and no NPU execution backend.

NPU support converts a per-SoC compiled model into a first-class execution option on compatible
Snapdragon devices, with GPU/CPU remaining the universal fallback. The discovery that Qualcomm
publishes the QNN runtime as an official Maven artifact
(`com.qualcomm.qti:qnn-runtime`) makes this a normal optional runtime integration rather than a
distribution experiment.

## What Changes

### Dependency & packaging
- Add `com.qualcomm.qti:qnn-runtime` (version resolved in Spike 1, see design) as the official
  external QNN runtime dependency. No Qualcomm binaries are committed to Git, mirrored, or hosted
  by Loki; the Maven artifact is the only QNN sourcing channel.
- Build `libLiteRtDispatch_Qualcomm.so` (Apache-2.0 LiteRT source) from the LiteRT revision
  matching litertlm 0.16.1; package it via Gradle (not committed to Git).
- Enable `packaging { jniLibs { useLegacyPackaging = true } }` — mandatory: the dispatch locates
  vendor libraries via `readdir()` of `applicationInfo.nativeLibraryDir`, which is empty without it.
- Keep the artifact's license/NOTICE files in the final APK. Release gate: explicitly verify the
  pinned QNN version's license terms before first public release.
- The full Maven AAR (~67MB) is acceptable initially; trimming to the minimal AOT set
  (~25MB: dispatch + libQnnHtp + libQnnSystem + one Stub/Skel pair) is an optimization, not a
  prerequisite.

### Capability detection (hardware) — separate from model compatibility
- New device hardware probe (runs once per engine init): detects NPU vendor (`NpuVendor`:
  Qualcomm/MediaTek/Google Tensor/Samsung/Unknown) from `Build.SOC_MANUFACTURER`/`SOC_MODEL`,
  the HTP generation via the authoritative `supported_soc.csv` mapping (SM8750→v79, SM8850→v81,
  SM8650→v75, SM8550→v73, SM8450→v69), and `npuUsable` (dispatch + QNN libraries actually
  reachable from `nativeLibraryDir`). Probe results are exposed as observable engine capabilities.

### Model compatibility — advisory, not blocking
- Model records gain NPU execution metadata (target SoC / HTP generation, parsed from the
  `qualcomm_<soc>` artifact naming convention).
- Import does NOT hard-reject NPU models on SoC mismatch. A SoC-targeted model imports
  successfully but is marked **unavailable for execution** on non-matching devices, with a clear
  reason surfaced in the model library UI. It becomes executable when imported on a matching device.

### Backend resolution
- `ExecutionBackend.NPU` added; `AUTOMATIC` remains the primary/default backend and resolves
  the attempt chain **NPU → GPU → CPU**, gated by hardware `npuUsable` AND model compatibility.
- NPU is exposed as an **advanced/manual** selection (explicit requests are exclusive: a
  user-selected NPU that fails surfaces the error rather than silently swapping), NOT a normal
  Agent Playground radio option.

### Transactional, observable fallback
- Backend initialization is transactional: each attempt records (backend, duration, failure
  reason); partial native resources from a failed attempt are fully released before the next.
- The resolved outcome (attempted chain, per-attempt failures, final backend) is observable via
  engine state and logs, and the active backend is displayed in the UI.

### Validation-first
- All validation happens first on the SM8750 (Snapdragon 8 Elite) device using the Qualcomm E2B
  `.litertlm` model that exposed the original failure. No rollout of NPU paths without that test
  passing.

## Capabilities

### New Capabilities
- `npu-runtime`: Optional Qualcomm NPU execution backend — hardware capability probing, QNN
  runtime packaging via official Maven artifact, dispatch library build, and NPU engine
  initialization with transactional fallback.

### Modified Capabilities
- `llm-engine`: `ExecutionBackend` gains `NPU`; AUTOMATIC resolution order NPU→GPU→CPU; backend
  attempts are transactional and observable.
- `model-library`: SoC-targeted NPU models import successfully and are marked unavailable for
  execution (with reason) on non-matching devices instead of being rejected.

## Impact

- **Code**: `core/models` (`ExecutionBackend`, model NPU metadata), `core/llm` (probe, backend
  chain, `Backend.NPU` construction, sampler-skip when NPU active), `ModelManager`/import
  (advisory compatibility marking), `core/ui` (unavailable-for-execution badges, advanced NPU
  setting, active-backend display), `app` (Gradle deps, packaging, NOTICE).
- **Build**: `com.qualcomm.qti:qnn-runtime` dependency; CI step building
  `libLiteRtDispatch_Qualcomm.so` from pinned LiteRT source; `useLegacyPackaging = true`.
- **Not affected**: CPU/GPU behavior for generic models is unchanged; devices without NPU never
  enter NPU paths (`npuUsable=false` hides everything NPU-related).
- **Distribution**: monolithic APK via GitHub Releases unaffected; Play Feature Delivery is
  explicitly out of scope.
