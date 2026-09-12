package dev.loki.android.core.conversation

import dev.loki.android.core.tools.ToolResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("conversation_store_test").toFile()
        store = ConversationStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `create and load conversation returns matching record`() = runTest {
        val created = store.createConversation(title = "Test Conversation")
        assertNotNull(created.id)
        assertEquals("Test Conversation", created.title)
        assertTrue(created.turns.isEmpty())

        val loaded = store.loadConversation(created.id)
        assertNotNull(loaded)
        assertEquals(created.id, loaded?.id)
        assertEquals("Test Conversation", loaded?.title)
    }

    @Test
    fun `round-trip serialization preserves all ConversationTurn types`() = runTest {
        val conv = store.createConversation(title = "Multi-Turn")

        val userTurn = ConversationTurn.User("Turn on Bluetooth")
        val toolCallTurn = ConversationTurn.ToolCall("set_bluetooth", mapOf("enabled" to "true"))
        val toolResultTurn = ConversationTurn.ToolExecutionResult(
            "set_bluetooth",
            ToolResult.success(mapOf("status" to "enabled"))
        )
        val assistantTurn = ConversationTurn.Assistant("Bluetooth has been turned on.")

        store.appendTurn(conv.id, userTurn, autoTitle = false)
        store.appendTurn(conv.id, toolCallTurn, autoTitle = false)
        store.appendTurn(conv.id, toolResultTurn, autoTitle = false)
        store.appendTurn(conv.id, assistantTurn, autoTitle = false)

        val loaded = store.loadConversation(conv.id)
        assertNotNull(loaded)
        assertEquals(4, loaded?.turns?.size)

        val t0 = loaded?.turns?.get(0) as ConversationTurn.User
        assertEquals("Turn on Bluetooth", t0.text)

        val t1 = loaded?.turns?.get(1) as ConversationTurn.ToolCall
        assertEquals("set_bluetooth", t1.tool)
        assertEquals("true", t1.arguments["enabled"])

        val t2 = loaded?.turns?.get(2) as ConversationTurn.ToolExecutionResult
        assertEquals("set_bluetooth", t2.tool)
        assertTrue(t2.result.success)
        assertEquals("enabled", t2.result.data?.get("status"))

        val t3 = loaded?.turns?.get(3) as ConversationTurn.Assistant
        assertEquals("Bluetooth has been turned on.", t3.text)
    }

    @Test
    fun `autoTitle truncates first user prompt`() = runTest {
        val conv = store.createConversation(title = "New Chat")
        val longPrompt = "Please help me write a comprehensive summary of quantum mechanics and its modern applications"
        val updated = store.appendTurn(conv.id, ConversationTurn.User(longPrompt), autoTitle = true)

        assertNotNull(updated)
        assertTrue(updated!!.title.endsWith("…"))
        assertEquals(41, updated.title.length) // 40 chars + "…"
    }

    @Test
    fun `rename and delete conversation work correctly`() = runTest {
        val conv = store.createConversation(title = "Old Name")
        assertTrue(store.renameConversation(conv.id, "New Name"))

        val renamed = store.loadConversation(conv.id)
        assertEquals("New Name", renamed?.title)

        assertTrue(store.deleteConversation(conv.id))
        assertNull(store.loadConversation(conv.id))
    }

    @Test
    fun `listConversations ignores corrupt files without failing`() = runTest {
        val valid1 = store.createConversation(title = "Valid 1")
        val valid2 = store.createConversation(title = "Valid 2")

        // Create corrupt file
        val corruptFile = File(tempDir, "corrupt_conv.json")
        corruptFile.writeText("{ broken json content ... ")

        val list = store.listConversations()
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == valid1.id })
        assertTrue(list.any { it.id == valid2.id })
    }

    @Test
    fun `concurrent appendTurn calls preserve all turns`() = runTest {
        val conv = store.createConversation(title = "Concurrent Chat")
        val turn1 = ConversationTurn.User("Turn 1")
        val turn2 = ConversationTurn.User("Turn 2")

        kotlinx.coroutines.coroutineScope {
            val deferred1 = async { store.appendTurn(conv.id, turn1, autoTitle = false) }
            val deferred2 = async { store.appendTurn(conv.id, turn2, autoTitle = false) }
            kotlinx.coroutines.awaitAll(deferred1, deferred2)
        }

        val loaded = store.loadConversation(conv.id)
        assertNotNull(loaded)
        assertEquals(2, loaded?.turns?.size)
        val turnTexts = loaded?.turns?.map { (it as ConversationTurn.User).text }
        assertTrue(turnTexts?.contains("Turn 1") == true)
        assertTrue(turnTexts?.contains("Turn 2") == true)
    }

    @Test
    fun `searchTurns finds matching user and assistant turns across multiple conversations`() = runTest {
        val conv1 = store.createConversation(title = "Trip Planning")
        val conv2 = store.createConversation(title = "Study Notes")

        store.appendTurn(conv1.id, ConversationTurn.User("I need to study for my history exam"), autoTitle = false)
        store.appendTurn(conv1.id, ConversationTurn.Assistant("Here is a study plan for your exam."), autoTitle = false)
        store.appendTurn(conv2.id, ConversationTurn.User("Remind me about the math exam next week"), autoTitle = false)
        store.appendTurn(conv2.id, ConversationTurn.User("What is the weather today?"), autoTitle = false)

        val results = store.searchTurns("exam", limit = 5)
        assertEquals(3, results.size)
        assertTrue(results.any { it.conversationTitle == "Trip Planning" && it.snippet.contains("history exam") })
        assertTrue(results.any { it.conversationTitle == "Trip Planning" && it.snippet.contains("study plan") })
        assertTrue(results.any { it.conversationTitle == "Study Notes" && it.snippet.contains("math exam") })
    }

    @Test
    fun `searchTurns skips corrupt conversation files gracefully`() = runTest {
        val valid = store.createConversation(title = "Valid Chat")
        store.appendTurn(valid.id, ConversationTurn.User("Secret code is 9999"), autoTitle = false)

        val corruptFile = File(tempDir, "broken.json")
        corruptFile.writeText("{ invalid json }")

        val results = store.searchTurns("code", limit = 5)
        assertEquals(1, results.size)
        assertEquals("Valid Chat", results[0].conversationTitle)
        assertTrue(results[0].snippet.contains("9999"))
    }

    @Test
    fun `searchTurns returns empty list on empty query or no matches`() = runTest {
        val conv = store.createConversation(title = "Chat")
        store.appendTurn(conv.id, ConversationTurn.User("Hello world"), autoTitle = false)

        assertTrue(store.searchTurns("", limit = 5).isEmpty())
        assertTrue(store.searchTurns("   ", limit = 5).isEmpty())
        assertTrue(store.searchTurns("nonexistent_term", limit = 5).isEmpty())
    }
}
