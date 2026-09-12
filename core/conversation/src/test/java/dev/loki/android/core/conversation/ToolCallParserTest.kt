package dev.loki.android.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun testParseValidJsonToolCall() {
        val raw = """{"tool": "call_contact", "arguments": {"candidate_id": "c3", "name": "Mom"}}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("call_contact", toolCall.tool)
        assertEquals("c3", toolCall.arguments["candidate_id"])
        assertEquals("Mom", toolCall.arguments["name"])
    }

    @Test
    fun testParseValidJsonDirectResponse() {
        val raw = """{"response": "Hello, how can I help you?"}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        assertEquals("Hello, how can I help you?", direct.text)
    }

    @Test
    fun testParseFallback2StripsWrappingQuotes() {
        val rawSingle = "\"Which contact would you like to call?\""
        val parsedSingle = ToolCallParser.parse(rawSingle)
        assertTrue(parsedSingle is ParsedLlmResponse.DirectResponse)
        assertEquals("Which contact would you like to call?", (parsedSingle as ParsedLlmResponse.DirectResponse).text)

        val rawDouble = "\"\"Which contact would you like to call?\"\""
        val parsedDouble = ToolCallParser.parse(rawDouble)
        assertTrue(parsedDouble is ParsedLlmResponse.DirectResponse)
        assertEquals("Which contact would you like to call?", (parsedDouble as ParsedLlmResponse.DirectResponse).text)
    }

    @Test
    fun testParseBareAskUserRepairsToToolCall() {
        val parsed = ToolCallParser.parse("ask_user")
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("", toolCall.arguments["text"])
    }

    @Test
    fun testParseAskUserWithNewlineRepairsToToolCall() {
        val parsed = ToolCallParser.parse("ask_user\nWhich Mom would you like to call?")
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Which Mom would you like to call?", toolCall.arguments["text"])
    }

    @Test
    fun testParseAskUserWithSpaceRepairsToToolCall() {
        val parsed = ToolCallParser.parse("ask_user Which Mom would you like to call?")
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Which Mom would you like to call?", toolCall.arguments["text"])
    }

    @Test
    fun testParseAskUserPrefixOnlyNotMatchingIdentifier() {
        val parsed = ToolCallParser.parse("ask_user_custom_tool: do something")
        assertTrue("ask_user_custom_tool must NOT repair as ask_user", parsed !is ParsedLlmResponse.ToolCall)
    }

    @Test
    fun testParseAskUserWithColonRepairsToToolCall() {
        val parsed = ToolCallParser.parse("ask_user: Which Mom would you like to call?")
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Which Mom would you like to call?", toolCall.arguments["text"])
    }

    @Test
    fun testParseAskUserWithParenthesesRepairsToToolCall() {
        val parsed = ToolCallParser.parse("ask_user(\"Which Mom would you like to call?\")")
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Which Mom would you like to call?", toolCall.arguments["text"])
    }

    @Test
    fun testParseJsonWrappedAskUserUsesPrimaryPath() {
        val raw = """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom?"}}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Shall I call Mom?", toolCall.arguments["text"])
    }

    @Test
    fun testParseSideEffectingBareToolDoesNotRepair() {
        val parsed = ToolCallParser.parse("call_contact")
        assertTrue("Bare call_contact must NOT repair to ToolCall", parsed !is ParsedLlmResponse.ToolCall)
    }

    @Test
    fun testParseAskUserArgumentsAsStringRepairsToToolCall() {
        val raw = """{"tool": "ask_user", "arguments": "Would you like to call Badi mummy?"}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Would you like to call Badi mummy?", toolCall.arguments["text"])
    }

    @Test
    fun testParseAskUserTopLevelTextRepairsToToolCall() {
        val raw = """{"tool": "ask_user", "text": "Would you like to call Badi mummy?"}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        assertEquals("Would you like to call Badi mummy?", toolCall.arguments["text"])
    }

    @Test
    fun testParseSideEffectingToolStringArgumentsDoesNotGuessArguments() {
        val raw = """{"tool": "call_contact", "arguments": "Badi mummy"}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("call_contact", toolCall.tool)
        assertTrue("Side-effecting tools must not guess arguments from string", toolCall.arguments.isEmpty())
    }

    @Test
    fun testParseNaturalLanguageWithTrailingAskUserPreservesPreText() {
        val raw = """
            I can assist you with a variety of tasks! Here are some things I can do:
            * Contacts: Search and call contacts
            * Alarms: Set alarms
            
            What can I do for you right now? {"tool": "ask_user", "arguments": {"text": "What can I do for you right now?"}}
        """.trimIndent()
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        val text = toolCall.arguments["text"] as String
        assertTrue("Pre-text markdown should be preserved", text.contains("I can assist you with a variety of tasks!"))
        assertTrue("Contacts bullet should be preserved", text.contains("* Contacts: Search and call contacts"))
        assertTrue("Closing question should be preserved", text.contains("What can I do for you right now?"))
        assertFalse("Should not contain raw JSON markers in text", text.contains("{\"tool\""))
    }

    @Test
    fun testParseNaturalLanguageWithTrailingAskUserCombinesQuestion() {
        val raw = """
            Here is a list of features.
            {"tool": "ask_user", "arguments": {"text": "What would you like to try?"}}
        """.trimIndent()
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("ask_user", toolCall.tool)
        val text = toolCall.arguments["text"] as String
        assertTrue(text.contains("Here is a list of features."))
        assertTrue(text.contains("What would you like to try?"))
    }

    @Test
    fun testParseNaturalLanguageWithTrailingActionToolExtractsTool() {
        val raw = """Sure, searching for Alice now. {"tool": "lookup_contact", "arguments": {"query": "Alice"}}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        val toolCall = parsed as ParsedLlmResponse.ToolCall
        assertEquals("lookup_contact", toolCall.tool)
        assertEquals("Alice", toolCall.arguments["query"])
    }

    @Test
    fun testParseNaturalLanguageWithEmbeddedResponseCombinesText() {
        val raw = """Here is the information: {"response": "The weather today is 25 degrees and sunny."}"""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        assertTrue(direct.text.contains("Here is the information:"))
        assertTrue(direct.text.contains("The weather today is 25 degrees and sunny."))
    }

    @Test
    fun testParseNaturalLanguageWithCorruptedTrailingJsonRecoversPreText() {
        val raw = """I can assist you with many tasks: 1. Weather, 2. Alarms. {"tool": "ask_user", "arg""""
        val parsed = ToolCallParser.parse(raw)
        assertTrue(parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        assertEquals("I can assist you with many tasks: 1. Weather, 2. Alarms.", direct.text)
    }

    @Test
    fun testParseKotlinCodeBlockWithBracesAndNoToolMarkerIsDirectResponse() {
        // A fenced Kotlin/Java code block whose body contains { } braces but no "tool": key
        // must be treated as a DirectResponse, not a ToolCall or Malformed.
        val raw = """
            ```kotlin
            fun greet(name: String) {
                println("Hello, ${'$'}name!")
            }
            ```
        """.trimIndent()
        val parsed = ToolCallParser.parse(raw)
        assertTrue("Kotlin code block without \"tool:\" must be DirectResponse", parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        // The full un-truncated text must survive (both function signature and body).
        assertTrue("Response must preserve the function signature", direct.text.contains("fun greet"))
        assertTrue("Response must preserve the function body", direct.text.contains("println"))
    }

    @Test
    fun testParseMixedProseAndFencedCodeBlockIsDirectResponse() {
        // A response with natural-language prose before/after a fenced code block
        // (so the full string neither starts nor ends purely with ```) must be
        // returned as DirectResponse, never as Malformed.
        val raw = """
            Here is how you can do it:

            ```python
            print("Hello, World!")
            ```

            Let me know if you have any questions.
        """.trimIndent()
        val parsed = ToolCallParser.parse(raw)
        assertFalse("Mixed prose+code response must not be Malformed", parsed is ParsedLlmResponse.Malformed)
        assertTrue("Mixed prose+code response must be DirectResponse", parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        assertTrue("Full text including prose must be preserved", direct.text.contains("Here is how you can do it:"))
        assertTrue("Code block content must be preserved", direct.text.contains("print(\"Hello, World!\")"))
        assertTrue("Trailing prose must be preserved", direct.text.contains("Let me know if you have any questions."))
    }

    @Test
    fun testParseCapabilityListWithInlineBacktickCodeIsDirectResponse() {
        // A capability summary where tool names / commands appear as inline `backtick` code
        // (no fenced blocks, so the triple-backtick guard does not fire) must fall through
        // to the natural-language fallback and be returned as DirectResponse.
        val raw = """
            I can help you with the following:
            - Use `lookup_contact` to find contacts
            - Use `set_alarm` to set alarms
            - Use `send_message` to send messages

            What would you like to do?
        """.trimIndent()
        val parsed = ToolCallParser.parse(raw)
        assertTrue("Capability list with inline backticks must be DirectResponse", parsed is ParsedLlmResponse.DirectResponse)
        val direct = parsed as ParsedLlmResponse.DirectResponse
        assertTrue("Response must contain capability list text", direct.text.contains("lookup_contact"))
        assertTrue("Response must contain closing question", direct.text.contains("What would you like to do?"))
    }

    @Test
    fun testCleanStreamingPartialHidesToolCalls() {
        val toolCall1 = """{"tool": "call_contact""""
        val toolCall2 = "```json\n{\"tool\": \"call_contact\""
        val toolCall3 = "{\n  \"tool\": \"toggle_flashlight\""

        assertNull(ToolCallParser.cleanStreamingPartial(toolCall1))
        assertNull(ToolCallParser.cleanStreamingPartial(toolCall2))
        assertNull(ToolCallParser.cleanStreamingPartial(toolCall3))
    }

    @Test
    fun testCleanStreamingPartialUnwrapsResponseEnvelope() {
        val jsonEnvelope = "```json\n{\n  \"response\": \"Hello world!\"\n}\n```"
        val partial = "```json\n{\n  \"response\": \"Hello world"
        val bareJson = "{\"response\": \"Hi there\"}"

        assertEquals("Hello world!", ToolCallParser.cleanStreamingPartial(jsonEnvelope))
        assertEquals("Hello world", ToolCallParser.cleanStreamingPartial(partial))
        assertEquals("Hi there", ToolCallParser.cleanStreamingPartial(bareJson))
    }

    @Test
    fun testCleanStreamingPartialPreservesRealMarkdown() {
        val codeBlock = "Here is the code:\n```java\nclass Hello {}\n```"
        assertEquals(codeBlock, ToolCallParser.cleanStreamingPartial(codeBlock))
    }
}
