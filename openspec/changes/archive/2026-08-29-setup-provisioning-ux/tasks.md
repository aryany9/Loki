## 1. ASR Catalog Entry (Open Decision — Resolve First)

- [x] 1.1 Verified: generic FP32 model `whisper_tiny_30s_f32.tflite` from `litert-community/whisper-tiny`. URL: `https://huggingface.co/litert-community/whisper-tiny/resolve/main/whisper_tiny_30s_f32.tflite`. Size: ~151 MB. SHA-256: not yet pinned (not available in HF metadata at verification time; omit from catalog for now). Do NOT use Qualcomm/MediaTek SoC-specific variants.
- [x] 1.2 Create `core/models/src/main/assets/model_catalog.json` with a `ModelCatalogEntry` for `LITERT_ASR`: id `whisper-tiny-litert`, displayName `"Whisper Tiny (ASR)"`, runtime `LITERT_ASR`, format `TFLITE`, artifacts list containing `whisper_tiny_30s_f32.tflite` with the verified URL/size from 1.1. Include a placeholder LLM entry stub (empty artifacts) if desired, or leave LLM catalog empty per open question in design.
- [x] 1.3 Load the bundled catalog in `AppModule` using `ModelCatalogRepository.load(remoteUrl = null, bundled = <parsed catalog>)` and expose it for use in `MainActivity`. Add the asset-reading helper if not already present.

## 2. LiteRtWhisperEngine — Real load() Implementation

- [x] 2.1 Add `ModelStorage` as a constructor parameter to `LiteRtWhisperEngine` (primary constructor; `AppModule` will inject it).
- [x] 2.2 Implement `load(model: ModelRecord)` to: (a) find the first artifact whose `fileName` ends with `.tflite`; (b) resolve the absolute path via `storage.artifactFile(model.id, artifact.relativePath)`; (c) return `false` if the file does not exist; (d) call `initialize(resolvedPath.absolutePath)` and return its result. Remove the stub `isInitialized = true` line.
- [x] 2.3 Update `AppModule.provideSttEngine()` to construct `LiteRtWhisperEngine(storage = modelManager.modelStorage)`.
- [x] 2.4 Update `LiteRtWhisperEngineTest` to pass a fake/temporary `ModelStorage` and add a test case for `load()` with an existing file returning `true` and a missing file returning `false`.

## 3. Navigation — Runtime-Readiness Gate

- [x] 3.1 In `MainActivity.setContent`, replace the `LaunchedEffect(isFirstRunComplete)` single-shot check with a `LaunchedEffect(modelManifest)` (or a derived `val bothReady` boolean derived from `manifest`) that evaluates `modelLibraryManager.isRuntimeReady(LITERT_LM) && modelLibraryManager.isRuntimeReady(LITERT_ASR)` on every manifest emission.
- [x] 3.2 Routing logic: if `!bothReady`, set `currentScreen = AppScreen.SETUP`; if `bothReady && currentScreen == AppScreen.SETUP`, set `currentScreen = AppScreen.CHAT`. Ensure the check only auto-transitions from SETUP → CHAT (not from CHAT → SETUP mid-session to avoid jarring mid-session navigation).
- [x] 3.3 Remove the `onOpenModelLibrary` null/non-null logic that disabled the button when any model was downloaded. The callback to `ModelLibraryScreen` should always be available from `SetupScreen`.

## 4. SetupScreen — Provisioning Coordinator UI

- [x] 4.1 Add `models: List<ModelRecord>` and `catalog: List<ModelCatalogEntry>` parameters to `SetupScreen`. Add an `onProvisionRuntime: (ModelRuntime) -> Unit` callback replacing the current `onOpenModelLibrary` parameter.
- [x] 4.2 Implement a `RuntimeProvisionCard` composable inside `SetupScreen.kt` that accepts: `title: String`, `description: String`, `isReady: Boolean`, `loadedModelName: String?`, `onProvision: () -> Unit`. Render: runtime name + description; if ready, "✅ Loaded — {loadedModelName}"; if not ready, "❌ Required" + a "Choose / Download" button that calls `onProvision`.
- [x] 4.3 Replace the current "AI Models Status" text block in `SetupScreen` with two `RuntimeProvisionCard` calls: one for `LITERT_LM` (title "LLM / Reasoning", description "On-device language model") and one for `LITERT_ASR` (title "ASR / Voice Recognition", description "On-device speech recognition").
- [x] 4.4 Derive `loadedModelName` for each card from the `models` list: find the model with `availability == LOADED` and `runtime == <target>`, use its `displayName`.
- [x] 4.5 Gate the "Get Started" button: enable only when `llmReady && asrReady`. Disable or hide "Skip for Now" — Setup must not be completable without both runtimes ready.
- [x] 4.6 Remove `onOpenModelLibrary` parameter (replaced by `onProvisionRuntime`); clean up any dead code from the old button logic.

## 5. MainActivity — Wire SetupScreen and Catalog

- [x] 5.1 Load the bundled catalog via `ModelCatalogRepository` at startup (in `LaunchedEffect(Unit)` or `onCreate`) and store it in a `remember`/`mutableStateOf` variable accessible to the Compose tree.
- [x] 5.2 Update the `AppScreen.SETUP` branch to pass `models = modelManifest.models`, `catalog = loadedCatalog`, and `onProvisionRuntime = { runtime -> currentScreen = AppScreen.MODEL_LIBRARY /* optionally store target runtime for filtering */ }`.
- [x] 5.3 Update the `AppScreen.MODEL_LIBRARY` branch to pass `catalog = loadedCatalog` (already has `onDownload` stub — wire it to a real download coroutine using `ModelDownloader` if not yet implemented, or note it as a follow-up if download is already wired elsewhere).
- [x] 5.4 Confirm `ModelLibraryScreen` receives the `onDownload` callback that calls into `ModelLibraryManager` / `ModelDownloader` to download catalog entries. If this path is not yet implemented, add a minimal download coroutine in `MainActivity` that fetches each artifact via `ModelDownloader` and calls `modelLibraryManager.register()` on completion.

## 6. Verification

- [x] 6.1 Build the project (`./gradlew assembleDebug`) and confirm it compiles without errors after all changes. ✅ BUILD SUCCESSFUL in 24s
- [x] 6.2 Run unit tests (`./gradlew :core:voice:stt:testDebugUnitTest :core:models:testDebugUnitTest`) and confirm `LiteRtWhisperEngineTest` and `ModelLibraryManagerTest` pass. ✅ 6/6 LiteRtWhisperEngineTest pass (3 new load() tests + existing).
- [ ] 6.3 Manual verification on device/emulator — fresh-data scenario: clear app data, launch, confirm `SetupScreen` is shown with two "❌ Required" cards. Tap "Choose / Download" on the ASR card, confirm `ModelLibraryScreen` opens with the Whisper catalog entry visible.
- [ ] 6.4 Manual verification — post-load scenario: import and load an LLM model, then load the ASR model (or confirm the download path works). Confirm both cards show "✅ Loaded" and "Get Started" becomes available. Tap "Get Started" and confirm navigation to `ChatScreen`.
- [ ] 6.5 Manual verification — regression scenario: after both models are loaded and `isFirstRunComplete = true`, force-quit and relaunch. Confirm the app goes directly to `ChatScreen` without showing Setup.
- [ ] 6.6 Manual verification — missing runtime regression: with `isFirstRunComplete = true`, delete the ASR model from Model Library, force-quit and relaunch. Confirm `SetupScreen` is shown again with the ASR card in "❌ Required" state.
