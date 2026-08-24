package dev.loki.android.llm

import android.util.Log
import org.json.JSONObject

data class BenchmarkResult(
    val prompt: String,
    val output: String,
    val latencyMs: Long,
    val timeToFirstTokenMs: Long,
    val isValidJson: Boolean,
    val detectedTool: String?,
    val isCorrect: Boolean
)

data class BenchmarkSummary(
    val totalTests: Int,
    val validJsonCount: Int,
    val correctToolCount: Int,
    val medianLatencyMs: Long,
    val medianTtftMs: Long,
    val results: List<BenchmarkResult>
)

object Spike2Benchmark {

    val sampleTools = listOf(
        ToolDefinition("call_contact", "Call a phone contact by name", mapOf("name" to ParamType.STRING)),
        ToolDefinition("dial_number", "Dial a phone number directly", mapOf("number" to ParamType.STRING)),
        ToolDefinition("open_app", "Launch an application by name", mapOf("name" to ParamType.STRING)),
        ToolDefinition("get_battery_status", "Get current battery level and status"),
        ToolDefinition("get_current_time", "Get the current time and date"),
        ToolDefinition("set_timer", "Set a countdown timer", mapOf("duration_seconds" to ParamType.NUMBER)),
        ToolDefinition("set_alarm", "Set an alarm for a specific time", mapOf("time" to ParamType.STRING)),
        ToolDefinition("media_control", "Control media playback", mapOf("action" to ParamType.STRING))
    )

    val testPrompts = listOf(
        "Call Rahul" to "call_contact",
        "Call Mom right now" to "call_contact",
        "Dial 9876543210" to "dial_number",
        "Open YouTube Music" to "open_app",
        "Launch WhatsApp" to "open_app",
        "Open Camera" to "open_app",
        "What's my battery percentage?" to "get_battery_status",
        "How much battery is left?" to "get_battery_status",
        "Is the phone charging?" to "get_battery_status",
        "What time is it?" to "get_current_time",
        "Tell me the current time" to "get_current_time",
        "Set a timer for 10 minutes" to "set_timer",
        "Start a 5 minute countdown timer" to "set_timer",
        "Set an alarm for 7 AM" to "set_alarm",
        "Wake me up at 6:30 tomorrow" to "set_alarm",
        "Pause the music" to "media_control",
        "Resume playback" to "media_control",
        "Next track please" to "media_control",
        "Skip this song" to "media_control",
        "Hello, who are you?" to "direct_response"
    )

    suspend fun runBenchmark(engine: LlmEngine, onProgress: (Int, Int, BenchmarkResult) -> Unit): BenchmarkSummary {
        val grammar = GrammarBuilder.buildFromTools(sampleTools)
        val results = mutableListOf<BenchmarkResult>()

        val systemPrompt = """
You are Loki, a hands-free Android voice assistant. Select the appropriate tool for the user request.
Available tools:
${sampleTools.joinToString("\n") { "- ${it.name}: ${it.description}" }}
Respond with a JSON object calling the tool or giving a direct response.
        """.trimIndent()

        for ((index, pair) in testPrompts.withIndex()) {
            val (userPrompt, expectedTool) = pair
            val fullPrompt = "<system>\n$systemPrompt\n</system>\n<user>\n$userPrompt\n</user>\n<assistant>\n"

            var firstTokenTime = 0L
            val startTime = System.currentTimeMillis()

            val response = engine.generate(
                prompt = fullPrompt,
                grammar = grammar,
                maxTokens = 128
            ) { _ ->
                if (firstTokenTime == 0L) {
                    firstTokenTime = System.currentTimeMillis()
                }
            }

            val endTime = System.currentTimeMillis()
            val totalLatency = endTime - startTime
            val ttft = if (firstTokenTime > 0) firstTokenTime - startTime else totalLatency

            val outputText = response.getOrDefault("")
            var isValidJson = false
            var detectedTool: String? = null

            try {
                val json = JSONObject(outputText)
                isValidJson = true
                if (json.has("tool")) {
                    detectedTool = json.getString("tool")
                } else if (json.has("response")) {
                    detectedTool = "direct_response"
                }
            } catch (e: Exception) {
                Log.w("Spike2Benchmark", "Invalid JSON output: $outputText")
            }

            val isCorrect = (expectedTool == detectedTool)

            val result = BenchmarkResult(
                prompt = userPrompt,
                output = outputText,
                latencyMs = totalLatency,
                timeToFirstTokenMs = ttft,
                isValidJson = isValidJson,
                detectedTool = detectedTool,
                isCorrect = isCorrect
            )

            results.add(result)
            onProgress(index + 1, testPrompts.size, result)
        }

        val sortedLatencies = results.map { it.latencyMs }.sorted()
        val sortedTtft = results.map { it.timeToFirstTokenMs }.sorted()

        val medianLatency = if (sortedLatencies.isNotEmpty()) sortedLatencies[sortedLatencies.size / 2] else 0L
        val medianTtft = if (sortedTtft.isNotEmpty()) sortedTtft[sortedTtft.size / 2] else 0L

        return BenchmarkSummary(
            totalTests = results.size,
            validJsonCount = results.count { it.isValidJson },
            correctToolCount = results.count { it.isCorrect },
            medianLatencyMs = medianLatency,
            medianTtftMs = medianTtft,
            results = results
        )
    }
}
