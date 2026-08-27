# Model Library

The model library stores installed artifacts under the app-specific external files directory:

```text
<external files>/models/
  models.json
  models/<model-id>/<artifact>
```

`models.json` is a versioned Kotlin-serialization manifest. Its active model is identified by model ID, not by `model.bin` or `model.gguf` existence. Imported and downloaded artifacts are first written as `<artifact>.part`, optionally checked for size and SHA-256, runtime-validated, and atomically finalized before registration.

Model lifecycle is explicit: `DOWNLOADED` means installed but unloaded, `LOADED` means the single active runtime model, and `NOT_DOWNLOADED` means registry metadata exists but the artifact is unavailable. Eject unloads runtime resources and retains the record and artifact. Delete is explicit and removes the record and artifact.

GGUF detection uses the file magic. LiteRT-LM validation uses temporary `LlmInference` initialization through the pinned MediaPipe API when selected by the caller. The current runtime does not provide a generic metadata-only LiteRT-LM detector, so unknown identity fields remain unknown until user confirmation. The filename `gemma-2b-it-cpu-int4.bin` is a hint only.

Remote catalog retrieval falls back to a bundled catalog supplied by the application. HTTP Range resume is not part of the first implementation; interrupted `.part` files are ignored or cleaned before a later attempt.