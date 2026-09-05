# Tasks: add-npu-backend-support

## 1. Spike: QNN ↔ LiteRT-LM version pairing (GATE — resolves Open Question D1)
- [x] 1.1 Inspect litertlm 0.16.1 POM/transitive dependencies and the LiteRT repo
      `third_party/qairt/workspace.bzl` at the revision matching litertlm 0.16.1; record the
      candidate QNN version (expected 2.42.x).
- [x] 1.2 On SM8750 device: add `com.qualcomm.qti:qnn-runtime:<candidate>` + staged dispatch,
      attempt NPU init with `gemma-4-E2B-it_qualcomm_sm8750.litertlm`, verify via logcat that
      the NPU accelerator registers and generation completes.
- [x] 1.3 Record the confirmed pin in `gradle/libs.versions.toml` and in this change's design
      (D1). If 2.42.0 fails, iterate adjacent QNN versions and record findings.

## 2. Packaging & sourcing (no Qualcomm binaries in Git)
- [x] 2.1 Add `com.qualcomm.qti:qnn-runtime:<pinned>` to `app`/`core:llm` Gradle config.
- [x] 2.2 Set up CI step (or documented one-time local step) building
      `libLiteRtDispatch_Qualcomm.so` from the LiteRT revision matching litertlm 0.16.1; stage
      it into the APK via Gradle (source dir gitignored).
- [x] 2.3 Enable `packaging { jniLibs { useLegacyPackaging = true } }` with the explanatory
      comment (dispatch `readdir()` requirement).
- [x] 2.4 Add `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`
      to `AndroidManifest.xml`.
- [x] 2.5 Verify the AAR's LICENSE/NOTICE files are retained in the built APK and add the
      release-gate checklist item: verify pinned QNN version's license permits in-app bundling.
- [x] 2.6 Add R8/ProGuard keep rules for `com.google.ai.edge.litertlm.**` and
      `com.qualcomm.**` (JNI/native entrypoints) so release builds cannot break `dlopen`/JNI
      binding (design D9).
- [x] 2.7 Add `scripts/fetch_dispatch.sh` + Gradle verification task for the staged
      `libLiteRtDispatch_Qualcomm.so`: fresh-clone `./gradlew` builds succeed without the
      toolchain; missing `.so` logs a clear warning and yields `npuUsable=false` (never a build
      failure) (design D8).

## 3. Hardware capability probe (separate from model compatibility)
- [x] 3.1 Add pure `NpuVendor` detection (Qualcomm/MediaTek/GoogleTensor/Samsung/Unknown) from
      SoC properties — no Android dependencies, unit-testable.
- [x] 3.2 Add the pinned `supported_soc.csv` mapping (SoC → HTP generation) as a lookup table.
- [x] 3.3 Add `npuUsable` filesystem check (QNN libs + `libLiteRtDispatch_Qualcomm.so` present
      in `applicationInfo.nativeLibraryDir`).
- [x] 3.4 Add `BackendCapabilities` probe result exposed via engine state; probe runs once per
      engine init.
- [x] 3.5 Unit tests for vendor detection, generation mapping, and usable checks.

## 4. Model compatibility metadata (advisory)
- [x] 4.1 Extend `LitertLmContainerInspector`: `Info` gains `npuTargetSoc`/`isNpuTargeted`,
      detected from container header/metadata content with the `qualcomm_<soc>` filename
      convention as fallback (design D7). Unit tests with synthetic header bytes.
- [x] 4.2 Update `LiteRtModelValidator`: NPU-targeted artifacts validate **structurally** (magic,
      metadata table, readability — no `Engine.initialize()`); generic models keep existing
      live-init validation (design D7). Unit tests for both paths.
- [x] 4.3 Extend model records with NPU target metadata (`npuTargetSoc`/generation) sourced from
      the inspector result at import (filename convention no longer the sole signal).
- [x] 4.4 Compute model-vs-device NPU compatibility from probe + record; do NOT reject imports
      on mismatch.
- [x] 4.5 Unit tests: `qualcomm_sm8750` on SM8750 (compatible), on non-matching SoC
      (unavailable-for-execution), generic model (no NPU target), renamed NPU container
      (metadata-detected where the header declares it).

## 5. Engine: NPU backend chain (transactional & observable)
- [x] 5.1 Add `ExecutionBackend.NPU` to `RuntimeConfig`/`ModelTypes.kt`.
- [x] 5.2 Implement backend candidate resolution: AUTOMATIC → NPU→GPU→CPU (NPU gated by
      `npuUsable && modelCompatible`); explicit NPU = single-candidate exclusive chain.
- [x] 5.3 Implement transactional attempts in `LiteRtLlmEngine`: full native release between
      candidates; construct `Backend.NPU(nativeLibraryDir)`; skip `samplerConfig` customization
      when NPU is active.
- [x] 5.4 Implement `EngineInitReport` (per-attempt backend, durationMs, outcome,
      failureReason; final backend) surfaced through `LlmModelState` and logs.
- [x] 5.5 Add repeated-failure backoff for NPU attempts (backend not retried after N
      consecutive failures until re-init).
- [x] 5.6 Unit tests: chain ordering, gating, exclusive explicit NPU, transactional release,
      report contents, sampler-skip.

## 6. UI (advisory + advanced)
- [x] 6.1 Model library: show "Unavailable for execution on this device" state with reason for
      SoC-mismatched NPU models (importable, not loadable).
- [x] 6.2 Surface active backend + init fallback report in engine status (chat/playground
      status area).
- [x] 6.3 Add NPU as an advanced (non-Playground) backend selection, visible only when
      `npuUsable`; explicit-failure error path surfaces report.

## 8. Post-implementation corrections (cross-check findings — required before archive)
- [x] 8.1 Remove `qnn-litert-delegate` dependency (TFLite-delegate path; unused by litertlm
      `Backend.NPU`) unless a `.tflite` path is actually present.
- [x] 8.2 Add `app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so` to `.gitignore`
      (binaries must never be committed; fetched via `scripts/fetch_dispatch.sh`).
- [x] 8.3 Fix `NpuCapabilityProbe.isNpuUsable`: the dispatch check must look for
      `libLiteRtDispatch_Qualcomm.so` specifically — `liblitertlm_jni` is always present and
      makes the check vacuous.
- [x] 8.4 Replace the hardcoded NPU KV capacity (1024) with a metadata-derived value
      (container metadata) or load-time discovery with fail→fallback; remove the arbitrary
      `>2048` threshold.
- [x] 8.5 Implement the advisory "unavailable for execution" gating: a SoC-mismatched NPU model
      must not be selected for NPU/engine load (model library state + load guard).
- [x] 8.6 Implement repeated-failure backoff for NPU attempts (task 5.5, not yet implemented).
- [x] 8.7 Move the NPU backend option out of the standard Agent Playground radio row into the
      advanced settings (spec D4).
- [x] 8.8 Record Spike 1 evidence for the pinned QNN version (2.47.0 currently used without
      documented on-device pairing verification; D1 candidate was 2.42.0) — attach logcat proof
      of NPU init + generation on SM8750 to this change.
- [ ] 8.9 Revisit `call_contact` in-session auto-lookup/coaching: keep short-term but file
      follow-up to move contact-resolution policy out of `ConversationSession` into the
      agent/tool layer (out of scope here).

## 7. Device validation (SM8750 + Qualcomm E2B model — validation-first)

- [ ] 7.1 AUTOMATIC on SM8750 with `gemma-4-E2B-it_qualcomm_sm8750.litertlm`: NPU initializes,
      generation streams, report shows NPU active.
- [ ] 7.2 AUTOMATIC with generic GPU model (E4B): NPU attempt fails cleanly (expected — no NPU
      subgraphs), falls back GPU, report records reasons.
- [ ] 7.3 Explicit NPU failure path: force a failure (e.g. mismatched model), verify error
      surfaces without silent swap and native state is clean.
- [ ] 7.4 Unavailable-for-execution: verify a mismatched-SoC NPU model imports, shows the
      advisory state, and is never sent to the engine.
- [ ] 7.5 Confirm `libQnnHtpPrepare.so` necessity question (D Open Question) for AOT models.
