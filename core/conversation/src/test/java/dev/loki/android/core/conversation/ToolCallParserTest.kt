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
}
