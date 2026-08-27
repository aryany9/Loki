package dev.loki.android.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun `selected record determines runtime`() {
        val model = ModelRecord(
            id = "litert",
            displayName = "LiteRT model",
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            artifactPath = "models/litert/model.bin",
            artifactFileName = "model.bin",
            sizeBytes = 1,
            source = ModelSource.LOCAL_IMPORT,
            importedAtEpochMs = 1L
        )

        assertEquals(ModelRuntime.LITERT_LM, ModelSelection.runtimeFor(model))
    }

    @Test
    fun `no selected record has no runtime`() {
        assertNull(ModelSelection.runtimeFor(null))
    }

    @Test
    fun `installed LiteRT model is preferred before llama model`() {
        val llama = testModel("llama", ModelRuntime.LLAMA_CPP, ModelFormat.GGUF)
        val liteRt = testModel("litert", ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL)

        assertEquals("litert", ModelSelection.preferredInstalledModel(ModelManifest(models = listOf(llama, liteRt)))?.id)
    }

    private fun testModel(id: String, runtime: ModelRuntime, format: ModelFormat) = ModelRecord(
        id = id,
        displayName = id,
        runtime = runtime,
        format = format,
        artifactPath = "models/$id/model",
        artifactFileName = "model",
        sizeBytes = 1,
        source = ModelSource.LOCAL_IMPORT,
        importedAtEpochMs = 1L
    )
}
