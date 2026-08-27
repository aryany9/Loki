# Fix Model Loading and Validation Progress Feedback

The goal is to ensure the user receives constant visual feedback during model import (specifically during the heavy validation phase) and during model loading into memory for inference.

## User Review Required

> [!IMPORTANT]
> The `LinearProgressIndicator` in `ModelLibraryScreen` will be updated to show an indeterminate state when the progress value is negative. This will be used for both "Validation" and "Loading" phases where exact progress isn't known.

## Proposed Changes

### [core:ui]

#### [MODIFY] [ModelLibraryScreen.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/core/ui/src/main/java/dev/loki/android/core/ui/ModelLibraryScreen.kt)
- Update `LinearProgressIndicator` to support indeterminate state when `operationProgress < 0`.

---

### [app]

#### [MODIFY] [MainActivity.kt](file:///Users/aryanyadav/Documents/Development/MobileApp/Loki/app/src/main/java/dev/loki/android/ui/MainActivity.kt)
- Update `importGgufModel` to keep `modelOperationProgress` active during `finishImport`.
- Update `onConfirmImport` to set `modelOperationProgress = -1f` during `finishImport`.
- Update `finishImport` to ensure `modelOperationProgress` is reset only when done.
- Update `onLoad` action to set `modelOperationProgress = -1f` while `modelLibraryManager.load(modelId)` is executing.

## Verification Plan

### Automated Tests
- N/A (UI feedback verification)

### Manual Verification
1.  **Import Model:** Select a model to import. Verify the progress bar remains visible (potentially indeterminate) during the "metadata validation" phase.
2.  **Load Model:** Click "Load" on a downloaded model. Verify an indeterminate progress bar appears until the model is ready.
3.  **Error Handling:** Ensure that if an error occurs during validation or loading, the progress bar disappears and the error message is shown correctly.
