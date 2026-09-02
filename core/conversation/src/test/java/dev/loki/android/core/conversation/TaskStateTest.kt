package dev.loki.android.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateTest {

    @Test
    fun `ContactResolution state transitions - unresolved candidates require select_contact`() {
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890"),
            ContactCandidate("c2", "Mom Mobile", "9876543210")
        )
        val state = ContactResolution(candidates = candidates, selectedId = null, confirmed = false)

        assertFalse(state.resolved)
        assertEquals("select_contact", state.advancingTool)
    }

    @Test
    fun `ContactResolution state transitions - selected candidate requires call_contact`() {
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890"),
            ContactCandidate("c2", "Mom Mobile", "9876543210")
        )
        val state = ContactResolution(candidates = candidates, selectedId = "c1", confirmed = false)

        assertFalse(state.resolved)
        assertEquals("call_contact", state.advancingTool)
    }

    @Test
    fun `ContactResolution state transitions - confirmed state is resolved with null advancingTool`() {
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890")
        )
        val state = ContactResolution(candidates = candidates, selectedId = "c1", confirmed = true)

        assertTrue(state.resolved)
        assertNull(state.advancingTool)
    }

    @Test
    fun `ContactResolution with single candidate pre-selects candidate`() {
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890")
        )
        val state = ContactResolution(
            candidates = candidates,
            selectedId = if (candidates.size == 1) candidates[0].id else null,
            confirmed = false
        )

        assertEquals("c1", state.selectedId)
        assertEquals("call_contact", state.advancingTool)
        assertFalse(state.resolved)
    }
}
