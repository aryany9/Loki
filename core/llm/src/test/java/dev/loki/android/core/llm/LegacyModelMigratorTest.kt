package dev.loki.android.core.llm

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LegacyModelMigratorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-legacy").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `invalid legacy file is not adopted`() = runBlocking {
        val files = File(root, "files").apply { mkdirs() }
        File(files, "model.invalid").writeText("not-a-model")
        val storage = ModelStorage(File(root, "managed"))
        val registry = ModelRegistry(storage)

        val migrated = LegacyModelMigrator(
            context = null,
            storage = storage,
            registry = registry,
            legacyLocations = { listOf(File(files, "model.invalid")) }
        ).migrate()

        assertTrue(migrated.isEmpty())
        assertEquals(0, registry.load().models.size)
    }

    @Test
    fun `valid legacy litertlm is adopted once`() = runBlocking {
        val files = File(root, "files").apply { mkdirs() }
        val source = File(files, "model.litertlm").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
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
