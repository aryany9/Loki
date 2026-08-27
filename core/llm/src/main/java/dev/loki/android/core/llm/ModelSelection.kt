package dev.loki.android.core.llm

object ModelSelection {
    fun runtimeFor(model: ModelRecord?): ModelRuntime? = model?.runtime

    fun preferredInstalledModel(manifest: ModelManifest): ModelRecord? {
        return manifest.models
            .filter { it.availability == ModelAvailability.DOWNLOADED }
            .sortedBy { if (it.runtime == ModelRuntime.LITERT_LM) 0 else 1 }
            .firstOrNull()
    }
}
