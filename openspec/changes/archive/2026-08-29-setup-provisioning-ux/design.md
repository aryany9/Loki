## Context

Loki's centralized model-management infrastructure (`ModelLibraryManager`, `ModelRuntimeController`, `ModelRegistry`, `ModelStorage`, `ModelDownloader`, `ModelCatalogRepository`) is implemented and functional. `LiteRtLlmEngine` and `LiteRtWhisperEngine` both implement `ModelRuntimeController` and are registered with `ModelLibraryManager` in `AppModule`. `ModelLibraryScreen` already handles catalog display, download, import, load, and eject.

The gap is in the Setup layer:

1. **`SetupScreen`** only renders two lines of status text; it has no acquisition action for either runtime.
2. **Navigation** uses `isFirstRunComplete` (DataStore flag) as the sole gate, not runtime readiness. A user can bypass the models entirely.
3. **`onOpenModelLibrary`** is set to `null` in `MainActivity` whenever any model is already downloaded, silently disabling the only CTA on the screen.
4. **`LiteRtWhisperEngine.load(ModelRecord)`** fakes initialization (`isInitialized = true`) without resolving the file; the engine has a working `initialize(path: String)` but it's never called through the controller lifecycle.
5. **No bundled catalog asset exists** (`model_catalog.json` is absent from assets); `ModelLibraryScreen` receives an empty catalog list from `MainActivity`, so no download option ever appears.

## Goals / Non-Goals

**Goals:**
- Make `SetupScreen` a provisioning coordinator: one card per mandatory runtime showing live state + a CTA.
- Change startup navigation: enter Setup whenever any mandatory runtime is not `LOADED`; enter Chat only when both are `LOADED`.
- Keep `ModelLibraryScreen` as the sole model-acquisition UI; Setup delegates into it rather than duplicating it.
- Implement the real `LiteRtWhisperEngine.load()` path via `ModelStorage` injection.
- Add a bundled `model_catalog.json` with an ASR entry (URL is an open decision; see below).
- Wire `MainActivity` to pass the catalog to `ModelLibraryScreen`.
- Fix the `onOpenModelLibrary` null bug.

**Non-Goals:**
- Redesigning or replacing `ModelLibraryManager`, `ModelRegistry`, `ModelStorage`, or any other centralized infrastructure.
- Adding TTS provisioning to Setup; Loki uses Android system TTS.
- Adding a second in-Setup download progress UI; download happens inside `ModelLibraryScreen`.
- Any new model-management abstraction layer.

## Decisions

### Decision 1 — Setup as coordinator, not duplicator

**Choice**: `SetupScreen` shows runtime cards and routes the user into `ModelLibraryScreen` when a runtime is missing. It does not replicate catalog UI, download progress, or import dialogs.

**Rationale**: `ModelLibraryScreen` already handles all acquisition paths. Duplicating them in Setup creates two diverging UI surfaces to maintain. The user experience of being taken into the full Model Library for acquisition is acceptable; the key requirement is that Setup surfaces what is missing and provides a clear CTA.

**Alternative considered**: Inline download progress inside `SetupScreen` (mini-catalog card). Rejected because it would duplicate catalog rendering, download callbacks, and import dialog logic already implemented in `ModelLibraryScreen`.

### Decision 2 — Navigation gate: runtime readiness, not the flag

**Choice**: `MainActivity` evaluates `modelLibraryManager.manifest` (a `StateFlow`) and routes to `SetupScreen` if `!isRuntimeReady(LITERT_LM) || !isRuntimeReady(LITERT_ASR)`, regardless of `isFirstRunComplete`. The flag is preserved only as a UX detail (e.g., skipping animations on subsequent visits to Setup).

**Rationale**: The flag can be set `true` while models are missing (e.g., cleared app data after first run, model deleted from library). Using runtime state as the gate prevents Loki from starting an assistant session without required models.

**Implementation**: Replace the `LaunchedEffect(isFirstRunComplete)` single-shot check with a `LaunchedEffect(manifest)` (or a derived boolean) that re-evaluates on every manifest emission.

```
Startup flow:
  manifest emits
      ↓
  LITERT_LM LOADED && LITERT_ASR LOADED?
      yes → ChatScreen
      no  → SetupScreen
```

**Alternative considered**: Keep the flag as primary gate, add a secondary check only on first run. Rejected because it fails the "model deleted after first run" scenario.

### Decision 3 — `LiteRtWhisperEngine` receives `ModelStorage` via constructor (Option A)

**Choice**: `LiteRtWhisperEngine` is given a `ModelStorage` instance at construction time (injected by `AppModule`). `load(model)` resolves the artifact path via `storage.artifactFile(model.id, artifact.relativePath).absolutePath` and calls the existing `initialize(path)`.

**Rationale**: `ModelStorage` is already available in `AppModule` (`modelManager.modelStorage`). This matches how the LLM engine accesses storage through `ModelManager`. No new mechanism or interface change is needed.

**Alternative considered**: Pass the resolved path through a side-channel (e.g., a setter called before `load()`). Rejected as it creates temporal coupling outside the `ModelRuntimeController` lifecycle.

### Decision 4 — Bundled catalog with ASR entry; URL is an open decision

**Choice**: `model_catalog.json` is added to `core/models/src/main/assets/` and loaded via the existing `ModelCatalogRepository`. An `LITERT_ASR` entry for `whisper_tiny_30s_f32.tflite` is included with a `TODO` URL placeholder until the verified download URL is confirmed.

**Evidence from codebase**:
- `LiteRtWhisperEngine.kt` docstring: `litert-community/whisper-tiny`
- `voice-assistant-workflow` proposal/tasks: `whisper_tiny_30s_f32.tflite`
- No download URL appears anywhere in the codebase.

**Open Decision**: The Hugging Face download URL for `whisper_tiny_30s_f32.tflite` from `litert-community/whisper-tiny` must be verified (e.g., from `https://huggingface.co/litert-community/Whisper-tiny/resolve/main/whisper_tiny_30s_f32.tflite` or equivalent). The task that writes the catalog entry must confirm this URL before the download action is usable.

**Alternative considered**: Leave catalog empty until URL is confirmed. Rejected because the rest of the provisioning wiring can be implemented and tested with a placeholder; the URL is a single field substitution.

### Decision 5 — `SetupScreen` card CTA routes into `ModelLibraryScreen`, filtered by runtime

**Choice**: The CTA button on each runtime card in `SetupScreen` calls an `onProvisionRuntime(ModelRuntime)` callback. In `MainActivity`, this navigates to `AppScreen.MODEL_LIBRARY` (already exists). No new screen is added.

**Rationale**: Filtering `ModelLibraryScreen` by runtime (passing only models of that runtime + the matching catalog entries) helps the user focus, while reusing all existing download/import/load UI. The filter is passed as a parameter to `ModelLibraryScreen` without breaking the existing back-from-library navigation.

**Alternative considered**: A dedicated per-runtime provisioning sub-screen. Rejected as over-engineering; the full Model Library already provides the right UX.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| ASR catalog URL is wrong or the file is unavailable | Catalog entry has `TODO` URL; download will fail with an error surfaced by `ModelDownloader`. The catalog URL is a single-field fix. |
| `LiteRtWhisperEngine.load()` resolves path but the `.tflite` runtime pipeline is still a stub (`transcribePcmAudio` returns a placeholder string) | `load()` wires the real file; actual inference completeness is tracked separately in the `voice-assistant-workflow` change. This change only fixes the lifecycle path. |
| Navigation loop if both runtimes remain not-ready after user returns from Model Library | `LaunchedEffect(manifest)` ensures Setup re-renders reactively; once both are LOADED the app transitions automatically. No explicit back-stack pop needed. |
| `isFirstRunComplete = true` written before models are ready | Flag is only written at the moment the user taps "Get Started" when both runtimes are confirmed LOADED. The navigation gate always re-checks runtime state regardless. |

## Open Questions

1. **Verified download URL for `whisper_tiny_30s_f32.tflite`**: Must be confirmed (expected: `https://huggingface.co/litert-community/Whisper-tiny/resolve/main/whisper_tiny_30s_f32.tflite` or similar). This is a blocker for the catalog entry's `url` field and the `sizeBytes` / `sha256` fields.
2. **LLM catalog entry**: Should the bundled catalog also include a default LLM entry (e.g., a small Gemma variant) so a fresh install has a download path for the LLM runtime? Or is import-only acceptable for LLM? Current `ModelLibraryScreen` shows an "Import" button but no downloadable LLM entry.
