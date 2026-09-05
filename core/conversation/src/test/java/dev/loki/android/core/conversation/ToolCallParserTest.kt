package dev.loki.android.core.conversation

import org.junit.Assert.assertEquals
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
}
