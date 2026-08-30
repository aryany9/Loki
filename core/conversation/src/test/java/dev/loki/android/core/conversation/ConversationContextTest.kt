package dev.loki.android.core.conversation

import dev.loki.android.core.llm.ModelPromptFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {
    @Test
    fun `Gemma prompt uses model turn markers instead of ChatML markers`() {
        val context = ConversationContext()
        context.append(ConversationTurn.User("hi"))

        val prompt = context.buildPrompt("Return JSON", ModelPromptFormat.GEMMA)

        assertTrue(prompt.startsWith("<start_of_turn>user\nReturn JSON\n\nhi<end_of_turn>\n"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
        assertFalse(prompt.contains("<|im_start|>"))
    }
}
