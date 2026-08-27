package dev.loki.android.core.llm

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class LegacyModelMigratorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-legacy").toFile()
    }

    @Test
    fun `invalid legacy bin is not adopted`() = runBlocking {
        val files = File(root, "files").apply { mkdirs() }
        File(files, "model.bin").writeText("not-a-model")
        val storage = ModelStorage(File(root, "managed"))
        val registry = ModelRegistry(storage)

        val migrated = LegacyModelMigrator(
            context = null,
            storage = storage,
            registry = registry,
            legacyLocations = { listOf(File(files, "model.bin")) }
        ).migrate()

        assertTrue(migrated.isEmpty())
        assertEquals(0, registry.load().models.size)
    }

    @Test
    fun `valid legacy gguf is adopted once`() = runBlocking {
        val files = File(root, "files").apply { mkdirs() }
        val source = File(files, "model.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
        val storage = ModelStorage(File(root, "managed"))
        val registry = ModelRegistry(storage)
        val migrator = LegacyModelMigrator(null, storage, registry, legacyLocations = { listOf(source) })

        val first = migrator.migrate()
        val second = migrator.migrate()

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertTrue(File(storage.rootDirectory, first.single().artifactPath).isFile)
        assertFalse(File(storage.rootDirectory, "models.json").readText().isEmpty())
    }
}
