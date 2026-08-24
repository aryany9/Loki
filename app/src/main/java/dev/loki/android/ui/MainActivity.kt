package dev.loki.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.loki.android.llm.BenchmarkSummary
import dev.loki.android.llm.GrammarBuilder
import dev.loki.android.llm.LlamaCppLlmEngine
import dev.loki.android.llm.Spike2Benchmark
import dev.loki.android.voice.AudioRecorder
import dev.loki.android.voice.LocalTtsEngine
import dev.loki.android.voice.Spike3Benchmark
import dev.loki.android.voice.Spike3Summary
import dev.loki.android.voice.WhisperBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Spike 2 State
    var modelPath by remember {
        val defaultPath = File(context.getExternalFilesDir(null), "model.gguf").absolutePath
        mutableStateOf(defaultPath)
    }
    var isLoadingModel by remember { mutableStateOf(false) }
    var isModelLoaded by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<LlamaCppLlmEngine?>(null) }
    var isRunningBenchmark by remember { mutableStateOf(false) }
    var benchmarkSummary by remember { mutableStateOf<BenchmarkSummary?>(null) }
    var currentProgressText by remember { mutableStateOf("") }
    var singlePrompt by remember { mutableStateOf("Call Rahul") }
    var singleOutput by remember { mutableStateOf("") }
    var isGeneratingSingle by remember { mutableStateOf(false) }

    // Spike 3 State (Whisper + VAD + TTS)
    var whisperPath by remember {
        val defaultPath = File(context.getExternalFilesDir(null), "whisper.bin").absolutePath
        mutableStateOf(defaultPath)
    }
    var whisperHandle by remember { mutableStateOf(0L) }
    var isLoadingWhisper by remember { mutableStateOf(false) }
    var isWhisperLoaded by remember { mutableStateOf(false) }

    val ttsEngine = remember { LocalTtsEngine(context) }
    val audioRecorder = remember { AudioRecorder() }

    var isRecordingAudio by remember { mutableStateOf(false) }
    var audioRms by remember { mutableFloatStateOf(0f) }
    var liveTranscript by remember { mutableStateOf("") }
    var livePipelineStatus by remember { mutableStateOf("") }

    var isRunningSpike3Benchmark by remember { mutableStateOf(false) }
    var spike3Summary by remember { mutableStateOf<Spike3Summary?>(null) }
    var spike3ProgressText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission required for STT", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsEngine.close()
            audioRecorder.stop()
            if (whisperHandle != 0L) {
                WhisperBridge.nativeFreeWhisper(whisperHandle)
            }
        }
    }

    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚡ Loki",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Offline-First Android Voice Assistant",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Spike 1 Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Spike 1: Assistant Role (Validated ✓)", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Default Assistant Settings", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Spike 2 Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Spike 2: llama.cpp + GBNF Tool Calling (Validated ✓)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = modelPath,
                    onValueChange = { modelPath = it },
                    label = { Text("Model GGUF Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            isLoadingModel = true
                            scope.launch {
                                try {
                                    val newEngine = withContext(Dispatchers.IO) {
                                        engine?.close()
                                        LlamaCppLlmEngine(modelPath = modelPath)
                                    }
                                    isModelLoaded = newEngine.isReady()
                                    engine = newEngine
                                } catch (e: Exception) {
                                    isModelLoaded = false
                                } finally {
                                    isLoadingModel = false
                                }
                            }
                        },
                        enabled = !isLoadingModel,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoadingModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Loading...")
                        } else {
                            Text(if (isModelLoaded) "Reload LLM" else "Load GGUF Model")
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isModelLoaded) "✓ Loaded" else "Not Loaded",
                        fontWeight = FontWeight.Bold,
                        color = if (isModelLoaded) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Spike 3 Card (Whisper STT + VAD + TTS)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Spike 3: Whisper.cpp + VAD + TTS Pipeline", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = whisperPath,
                    onValueChange = { whisperPath = it },
                    label = { Text("Whisper Model Path (bin)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            isLoadingWhisper = true
                            scope.launch {
                                try {
                                    val handle = withContext(Dispatchers.IO) {
                                        if (whisperHandle != 0L) WhisperBridge.nativeFreeWhisper(whisperHandle)
                                        WhisperBridge.nativeInitWhisper(whisperPath, 4)
                                    }
                                    whisperHandle = handle
                                    isWhisperLoaded = handle != 0L
                                } catch (e: Exception) {
                                    isWhisperLoaded = false
                                } finally {
                                    isLoadingWhisper = false
                                }
                            }
                        },
                        enabled = !isLoadingWhisper,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoadingWhisper) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Loading STT...")
                        } else {
                            Text(if (isWhisperLoaded) "Reload Whisper" else "Load Whisper Model")
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isWhisperLoaded) "✓ Loaded" else "Not Loaded",
                        fontWeight = FontWeight.Bold,
                        color = if (isWhisperLoaded) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Live Mic + VAD + Pipeline Button
                Button(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasPerm) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Button
                        }

                        if (isRecordingAudio) {
                            audioRecorder.stop()
                            isRecordingAudio = false
                        } else {
                            isRecordingAudio = true
                            liveTranscript = ""
                            livePipelineStatus = "Listening (speak now)..."

                            scope.launch {
                                audioRecorder.startListeningWithVad(
                                    silenceThresholdRms = 0.015f,
                                    silenceDurationMs = 400L,
                                    listener = object : AudioRecorder.VadListener {
                                        override fun onSpeechStart() {
                                            livePipelineStatus = "Speech detected, listening..."
                                        }

                                        override fun onSpeechEnd(audioSamples: FloatArray) {
                                            isRecordingAudio = false
                                            livePipelineStatus = "Transcribing audio with Whisper..."
                                            scope.launch {
                                                val startStt = System.currentTimeMillis()
                                                val text = if (whisperHandle != 0L) {
                                                    withContext(Dispatchers.IO) {
                                                        WhisperBridge.nativeTranscribe(
                                                            whisperHandle,
                                                            audioSamples,
                                                            audioSamples.size,
                                                            "en"
                                                        )
                                                    }
                                                } else {
                                                    "Whisper model not loaded"
                                                }
                                                val sttDuration = System.currentTimeMillis() - startStt
                                                liveTranscript = text.trim()
                                                livePipelineStatus = "STT (${sttDuration}ms): \"$liveTranscript\""

                                                if (engine != null && liveTranscript.isNotEmpty()) {
                                                    livePipelineStatus = "Generating tool call..."
                                                    val grammar = GrammarBuilder.buildFromTools(Spike2Benchmark.sampleTools)
                                                    val prompt = "<system>\nYou are Loki Android Assistant.\n</system>\n<user>\n$liveTranscript\n</user>\n<assistant>\n"
                                                    val res = engine!!.generate(prompt, grammar = grammar).getOrDefault("{}")
                                                    livePipelineStatus = "Tool: $res"

                                                    ttsEngine.speak("Executing action for $liveTranscript")
                                                }
                                            }
                                        }

                                        override fun onRmsChanged(rms: Float) {
                                            audioRms = (rms * 10f).coerceIn(0f, 1f)
                                        }
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isRecordingAudio) "⏹ Stop Recording" else "🎤 Speak into Mic (VAD Auto-Stop)")
                }

                if (isRecordingAudio) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Audio Level (RMS):", fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { audioRms },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }

                if (livePipelineStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(livePipelineStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 10-Utterance E2E Latency Benchmark
                Button(
                    onClick = {
                        if (engine == null) {
                            Toast.makeText(context, "Please load LLM model first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isRunningSpike3Benchmark = true
                        spike3Summary = null
                        scope.launch {
                            val audioSample = Spike3Benchmark.generateSyntheticSineWav(1.5f)
                            val summary = Spike3Benchmark.runEndToEndBenchmark(
                                whisperHandle = whisperHandle,
                                llmEngine = engine!!,
                                ttsEngine = ttsEngine,
                                sampleAudio = audioSample
                            ) { curr, total, res ->
                                spike3ProgressText = "Testing $curr/$total: STT=${res.sttLatencyMs}ms, LLM=${res.llmLatencyMs}ms"
                            }
                            spike3Summary = summary
                            isRunningSpike3Benchmark = false
                        }
                    },
                    enabled = !isRunningSpike3Benchmark && isModelLoaded,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isRunningSpike3Benchmark) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Running E2E Benchmark...")
                    } else {
                        Text("Run 10-Utterance E2E Latency Benchmark")
                    }
                }

                if (isRunningSpike3Benchmark) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(spike3ProgressText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                spike3Summary?.let { summary ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("E2E Pipeline Benchmark Results:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("• Total Test Utterances: ${summary.totalRuns}")
                            Text("• Median STT (Whisper): ${summary.medianSttMs} ms")
                            Text("• Median LLM (Tool Call): ${summary.medianLlmMs} ms")
                            Text("• Median TTS (Start to Speak): ${summary.medianTtsMs} ms")
                            Text(
                                "• Median Total E2E: ${summary.medianE2eMs} ms ${if (summary.isUnderTwoSeconds) "(✓ < 2s)" else "(> 2s)"}",
                                fontWeight = FontWeight.Bold,
                                color = if (summary.isUnderTwoSeconds) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                    }
                }
            }
        }
    }
}
