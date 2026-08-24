package dev.loki.android.core.assistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() {
        val session = AssistantSession()
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `startTurn transitions to Listening`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        assertTrue(session.state.value is AssistantState.Listening)
        session.destroy()
    }

    @Test
    fun `updateTranscript updates Listening state with partial transcript`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.updateTranscript("what time")
        val state = session.state.value
        assertTrue(state is AssistantState.Listening)
        assertEquals("what time", (state as AssistantState.Listening).partialTranscript)
        session.destroy()
    }

    @Test
    fun `updateProcessing transitions to Processing`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.updateProcessing("what time is it")
        val state = session.state.value
        assertTrue(state is AssistantState.Processing)
        assertEquals("what time is it", (state as AssistantState.Processing).query)
        session.destroy()
    }

    @Test
    fun `updateSpeaking transitions to Speaking`() = runTest {
        val session = AssistantSession()
        session.updateSpeaking("It's 4:00 PM")
        val state = session.state.value
        assertTrue(state is AssistantState.Speaking)
        assertEquals("It's 4:00 PM", (state as AssistantState.Speaking).responseText)
        session.destroy()
    }

    @Test
    fun `cancelTurn resets state to Idle`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.cancelTurn()
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `dismiss calls onDismiss callback and resets to Idle`() = runTest {
        var dismissed = false
        val session = AssistantSession(onDismissCallback = { dismissed = true })
        session.startTurn()
        session.dismiss()
        assertTrue(dismissed)
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }
}
