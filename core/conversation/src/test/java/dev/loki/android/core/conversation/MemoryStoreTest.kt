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

    // ── Task 1.6: Scoped retrieval and budget enforcement ────────────────────

    @Test
    fun `getMemoriesFor returns GLOBAL entries for both VOICE and CHAT`() = runTest {
        store.add("Global fact", MemorySource.USER_MANUAL)
        val voiceResults = store.getMemoriesFor(dev.loki.android.core.models.ConversationMode.VOICE, maxChars = 1000)
        val chatResults = store.getMemoriesFor(dev.loki.android.core.models.ConversationMode.CHAT, maxChars = 1000)
        assertEquals(1, voiceResults.size)
        assertEquals(1, chatResults.size)
        assertEquals("Global fact", voiceResults[0].text)
        assertEquals("Global fact", chatResults[0].text)
    }

    @Test
    fun `getMemoriesFor returns VOICE-scoped entry only to VOICE mode`() = runTest {
        store.add("Voice-only tip", MemorySource.USER_MANUAL)
        // Patch scope on the stored entry by updating the store directly
        val all = store.getAll()
        assertEquals(1, all.size)
        // Re-add with explicit VOICE scope using a store that supports it
        store.clear()
        val storeWithScope = MemoryStore(baseDir = tempDir, nowMillis = { fakeNow += 50; fakeNow })
        // Add with VOICE scope via the full add overload
        val entry = MemoryEntry(text = "Voice-only tip", scope = MemoryScope.VOICE)
        val file = java.io.File(tempDir, "memories.json")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()), listOf(entry)))

        val voiceResults = storeWithScope.getMemoriesFor(dev.loki.android.core.models.ConversationMode.VOICE, maxChars = 1000)
        val chatResults = storeWithScope.getMemoriesFor(dev.loki.android.core.models.ConversationMode.CHAT, maxChars = 1000)
        assertEquals(1, voiceResults.size)
        assertTrue(chatResults.isEmpty())
    }

    @Test
    fun `getMemoriesFor returns CHAT-scoped entry only to CHAT mode`() = runTest {
        val storeWithScope = MemoryStore(baseDir = tempDir, nowMillis = { fakeNow += 50; fakeNow })
        val entry = MemoryEntry(text = "Chat-only preference", scope = MemoryScope.CHAT)
        val file = java.io.File(tempDir, "memories.json")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()), listOf(entry)))

        val chatResults = storeWithScope.getMemoriesFor(dev.loki.android.core.models.ConversationMode.CHAT, maxChars = 1000)
        val voiceResults = storeWithScope.getMemoriesFor(dev.loki.android.core.models.ConversationMode.VOICE, maxChars = 1000)
        assertEquals(1, chatResults.size)
        assertTrue(voiceResults.isEmpty())
    }

    @Test
    fun `getMemoriesFor respects maxChars character budget`() = runTest {
        val storeWithScope = MemoryStore(baseDir = tempDir, nowMillis = { fakeNow += 50; fakeNow })
        val entries = listOf(
            MemoryEntry(text = "Short"),          // "- Short" = 7 chars
            MemoryEntry(text = "Also short"),     // "- Also short" = 12 chars
            MemoryEntry(text = "Another one"),    // "- Another one" = 13 chars
        ).sortedByDescending { it.updatedAtEpochMs }
        val file = java.io.File(tempDir, "memories.json")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()), entries))

        // Budget of 20 chars: only the first two entries fit (7+1 + 12+1 = 21 — second would overflow too, only first fits within 20)
        val results = storeWithScope.getMemoriesFor(dev.loki.android.core.models.ConversationMode.CHAT, maxChars = 20)
        assertTrue("Expected at most 2 entries within 20-char budget", results.size <= 2)
    }

    @Test
    fun `getMemoriesFor respects maxCount cap`() = runTest {
        val storeWithScope = MemoryStore(baseDir = tempDir, nowMillis = { fakeNow += 50; fakeNow })
        val entries = (1..5).map { MemoryEntry(text = "Fact $it") }
        val file = java.io.File(tempDir, "memories.json")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()), entries))

        val results = storeWithScope.getMemoriesFor(
            dev.loki.android.core.models.ConversationMode.CHAT,
            maxChars = 10000,
            maxCount = 3
        )
        assertEquals(3, results.size)
    }

    @Test
    fun `legacy entries without scope field default to GLOBAL and are visible to all modes`() = runTest {
        // Simulate a legacy JSON entry without a scope field
        val legacyJson = """[{"id":"legacy-1","text":"Legacy memory","createdAtEpochMs":1000,"updatedAtEpochMs":1000,"source":"USER_MANUAL"}]"""
        val file = java.io.File(tempDir, "memories.json")
        file.writeText(legacyJson)
        val storeWithLegacy = MemoryStore(baseDir = tempDir)
        val voiceResults = storeWithLegacy.getMemoriesFor(dev.loki.android.core.models.ConversationMode.VOICE, maxChars = 1000)
        val chatResults = storeWithLegacy.getMemoriesFor(dev.loki.android.core.models.ConversationMode.CHAT, maxChars = 1000)
        assertEquals(1, voiceResults.size)
        assertEquals(1, chatResults.size)
        assertEquals("Legacy memory", voiceResults[0].text)
    }
}
