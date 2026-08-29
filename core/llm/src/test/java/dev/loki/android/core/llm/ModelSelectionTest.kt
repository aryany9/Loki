package dev.loki.android.core.llm

import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelManifest
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun `preferred installed model selects first downloaded record`() {
        val model = ModelRecord(
            id = "litert",
            displayName = "LiteRT model",
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            artifacts = listOf(
                ModelArtifact(
                    fileName = "model.litertlm",
                    relativePath = "models/litert/model.litertlm",
                    sizeBytes = 1,
                    url = ""
                )
            ),
            source = ModelSource.LOCAL_IMPORT,
            importedAtEpochMs = 1L
        )

        assertEquals("litert", ModelSelection.preferredInstalledModel(ModelManifest(models = listOf(model)))?.id)
    }
}
