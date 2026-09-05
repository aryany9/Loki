package dev.loki.android.core.conversation

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryStoreTest {

    private lateinit var tempDir: File
    private var fakeNow = 1_000_000L
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("memory_store_test").toFile()
        fakeNow = 1_000_000L
        store = MemoryStore(
            baseDir = tempDir,
            nowMillis = { fakeNow += 50; fakeNow }
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `add, getAll, update, and delete perform full CRUD round-trip`() = runTest {
        val entry = store.add("My name is Arya", MemorySource.USER_MANUAL)
        assertNotNull(entry.id)
        assertEquals("My name is Arya", entry.text)
        assertEquals(MemorySource.USER_MANUAL, entry.source)
        assertEquals(1_000_050L, entry.createdAtEpochMs)
        assertEquals(1_000_050L, entry.updatedAtEpochMs)

        val list = store.getAll()
        assertEquals(1, list.size)
        assertEquals(entry.id, list[0].id)

        // Update
        val updatedSuccess = store.update(entry.id, "My name is Aryanyadav")
        assertTrue(updatedSuccess)

        val listAfterUpdate = store.getAll()
        assertEquals("My name is Aryanyadav", listAfterUpdate[0].text)
        assertEquals(1_000_100L, listAfterUpdate[0].updatedAtEpochMs)

        // Delete
        val deletedSuccess = store.delete(entry.id)
        assertTrue(deletedSuccess)
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `duplicate add with identical trimmed text dedupes and refreshes timestamp`() = runTest {
        val entry1 = store.add("My bike code is 4321", MemorySource.MODEL_TOOL)
        assertEquals(1_000_050L, entry1.updatedAtEpochMs)

        val updatedTime = entry1.updatedAtEpochMs + 50
        val entry2 = store.add("  My bike code is 4321  ", MemorySource.MODEL_TOOL, timestampEpochMs = updatedTime)

        assertEquals(entry1.id, entry2.id)
        assertEquals("My bike code is 4321", entry2.text)
        assertEquals(updatedTime, entry2.updatedAtEpochMs)

        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals(entry1.id, all[0].id)
        assertEquals(updatedTime, all[0].updatedAtEpochMs)
    }

    @Test
    fun `corrupt memory file degrades gracefully to empty list`() = runTest {
        val file = File(tempDir, "memories.json")
        file.writeText("{ invalid json content ...")

        val list = store.getAll()
        assertTrue(list.isEmpty())

        // Saving after corruption recovers cleanly
        val newEntry = store.add("Fresh start fact")
        assertEquals(1, store.getAll().size)
        assertEquals("Fresh start fact", store.getAll()[0].text)
        assertEquals(1_000_050L, newEntry.updatedAtEpochMs)
    }

    @Test
    fun `clear removes all memories`() = runTest {
        store.add("Fact 1")
        store.add("Fact 2")
        store.add("Fact 3")
        assertEquals(3, store.getAll().size)

        assertTrue(store.clear())
        assertTrue(store.getAll().isEmpty())
    }
}
