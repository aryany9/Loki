package dev.loki.android.core.llm

object ModelSelection {
    fun preferredInstalledModel(manifest: ModelManifest): ModelRecord? {
        return manifest.models
            .firstOrNull { it.availability == ModelAvailability.DOWNLOADED }
    }
}
