package dev.loki.android.core.models

import java.io.File

class ModelStorage(root: File) {
    val rootDirectory: File = root
    val manifestFile: File = File(rootDirectory, "models.json")

    fun modelDirectory(modelId: String): File = File(rootDirectory, "models/$modelId")

    fun artifactFile(modelId: String, relativePath: String): File =
        File(modelDirectory(modelId), relativePath)

    fun partialArtifactFile(modelId: String, relativePath: String): File =
        File(modelDirectory(modelId), "$relativePath.part")

    fun ensureDirectories() {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Unable to create model storage directory: ${rootDirectory.absolutePath}"
        }
    }
}
