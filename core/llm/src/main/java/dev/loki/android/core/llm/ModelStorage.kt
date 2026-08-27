package dev.loki.android.core.llm

import java.io.File

class ModelStorage(root: File) {
    val rootDirectory: File = root
    val manifestFile: File = File(rootDirectory, "models.json")

    fun modelDirectory(modelId: String): File = File(rootDirectory, "models/$modelId")

    fun artifactFile(modelId: String, fileName: String): File =
        File(modelDirectory(modelId), fileName)

    fun partialArtifactFile(modelId: String, fileName: String): File =
        File(modelDirectory(modelId), "$fileName.part")

    fun ensureDirectories() {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Unable to create model storage directory: ${rootDirectory.absolutePath}"
        }
    }
}
