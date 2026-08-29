package dev.loki.android.core.llm

import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelManifest
import dev.loki.android.core.models.ModelRecord

object ModelSelection {
    fun preferredInstalledModel(manifest: ModelManifest): ModelRecord? {
        return manifest.models
            .firstOrNull { it.availability == ModelAvailability.DOWNLOADED }
    }
}
