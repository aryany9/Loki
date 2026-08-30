package dev.loki.android.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.loki.android.core.llm.ModelAvailability
import dev.loki.android.core.llm.ModelFormat
import dev.loki.android.core.llm.ModelRecord
import dev.loki.android.core.llm.ModelRuntime
import dev.loki.android.core.llm.ModelSource
import org.junit.Rule
import org.junit.Test

class ModelLibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLibraryShowsImportAction() {
        composeRule.setContent {
            ModelLibraryScreen(
                models = emptyList(),
                onNavigateBack = {},
                onImport = {},
                onLoad = {},
                onEject = {},
                onDelete = {}
            )
        }

        composeRule.onNodeWithText("Import model").assertIsDisplayed()
        composeRule.onNodeWithText("No installed models").assertIsDisplayed()
    }

    @Test
    fun downloadedModelShowsLoadAction() {
        composeRule.setContent {
            ModelLibraryScreen(
                models = listOf(
                    ModelRecord(
                        id = "test",
                        displayName = "Test model",
                        runtime = ModelRuntime.LLAMA_CPP,
                        format = ModelFormat.GGUF,
                        artifactPath = "models/test/model.gguf",
                        artifactFileName = "model.gguf",
                        sizeBytes = 1,
                        source = ModelSource.LOCAL_IMPORT,
                        availability = ModelAvailability.DOWNLOADED,
                        importedAtEpochMs = 1L
                    )
                ),
                onNavigateBack = {},
                onImport = {},
                onLoad = {},
                onEject = {},
                onDelete = {}
            )
        }

        composeRule.onNodeWithText("Test model").assertIsDisplayed()
        composeRule.onNodeWithText("Load").assertIsDisplayed()
    }
}
