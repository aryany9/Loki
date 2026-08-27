package dev.loki.android.core.llm

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
            artifactPath = "models/litert/model.litertlm",
            artifactFileName = "model.litertlm",
            sizeBytes = 1,
            source = ModelSource.LOCAL_IMPORT,
            importedAtEpochMs = 1L
        )

        assertEquals("litert", ModelSelection.preferredInstalledModel(ModelManifest(models = listOf(model)))?.id)
    }
}
