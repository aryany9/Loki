## Why

The centralized model-management infrastructure (`ModelLibraryManager`, `ModelRuntimeController`, `LiteRtLlmEngine`, `LiteRtWhisperEngine`) is in place and proven functional. However, the Setup provisioning experience that was supposed to gate Loki on both mandatory runtimes being ready was never completed. The app's navigation currently uses a `isFirstRunComplete` DataStore flag as the sole gate, which can be bypassed, and the `SetupScreen` only displays status text — it provides no actionable path for a user to download or load the Whisper/ASR model. A fresh install therefore has no route to make the ASR runtime ready.

## What Changes

- **`SetupScreen`**: Replaced with a provisioning coordinator showing one card per mandatory runtime (`LITERT_LM`, `LITERT_ASR`). Each card reflects the runtime's actual state and provides a direct CTA ("Choose / Download") that takes the user into the existing `ModelLibraryScreen` filtered or pre-scoped to that runtime. No model-management logic is duplicated inside Setup.
- **Startup navigation** (`MainActivity`): The startup condition is changed from `isFirstRunComplete` to runtime readiness. The app enters `SetupScreen` if either mandatory runtime is not `LOADED`, regardless of the flag. The flag remains as a persisted UX state detail only.
- **`ModelLibraryManager.isRuntimeReady()`** remains unchanged; Setup observes the live `manifest` StateFlow so cards update reactively.
- **`LiteRtWhisperEngine.load(ModelRecord)`**: The current stub (sets `isInitialized = true` without resolving the file) is replaced with a real implementation. `ModelStorage` is injected into the engine (Option A) so `load()` resolves the `.tflite` artifact path via the centralized storage root and calls the existing `initialize(path)` method.
- **ASR model catalog entry**: A `ModelCatalogEntry` for `LITERT_ASR` (`whisper_tiny_30s_f32.tflite`) is added to the bundled catalog so `ModelLibraryScreen` can offer a download action for it. **The Hugging Face download URL for this artifact is an open decision** — the repository names `litert-community/whisper-tiny` and filename `whisper_tiny_30s_f32.tflite` are confirmed in source, but no verified download URL exists in the codebase. This must be resolved before the catalog entry can be finalized.
- **`AppModule`**: `LiteRtWhisperEngine` is constructed with `ModelStorage` injected; `ModelLibraryScreen` is passed the loaded catalog.
- **`onOpenModelLibrary` null bug** (`MainActivity`): The existing logic that sets this callback to `null` when any model is already downloaded is removed; the callback is always available from Setup.

## Capabilities

### New Capabilities
- `setup-provisioning`: Setup presents mandatory runtimes (LLM + ASR) with live state and an acquisition path for each; completion is gated on both runtimes being LOADED.

### Modified Capabilities
- `setup-and-permissions-screen`: Navigation gate changes from `isFirstRunComplete` flag to actual runtime readiness. Setup is shown when any mandatory runtime is not ready, even after `isFirstRunComplete` is `true`. Existing permission-display requirements are unchanged.

## Impact

- **`core/ui/src/.../SetupScreen.kt`**: New runtime provision card composables and updated completion logic.
- **`app/src/.../MainActivity.kt`**: Navigation condition, `onOpenModelLibrary` callback, catalog loading, and `ModelLibraryScreen` catalog wiring.
- **`core/voice/stt/src/.../LiteRtWhisperEngine.kt`**: Constructor gains `ModelStorage` parameter; `load()` is implemented for real.
- **`app/src/.../di/AppModule.kt`**: `provideSttEngine` and `provideModelLibraryManager` updated for `ModelStorage` injection.
- **`core/models/src/main/assets/model_catalog.json`** (new): Bundled catalog with LLM and ASR entries.
- **Open dependency**: Verified Hugging Face download URL for `whisper_tiny_30s_f32.tflite` must be confirmed before the ASR catalog entry's `url` field can be populated.
