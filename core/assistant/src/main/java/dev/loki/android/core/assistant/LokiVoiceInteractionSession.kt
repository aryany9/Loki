package dev.loki.android.core.assistant

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.loki.android.core.sound.AudioCue
import dev.loki.android.core.sound.audioStartCueEnabled
import dev.loki.android.core.theme.LokiTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * LokiVoiceInteractionSession manages an individual assistant invocation session.
 *
 * Responsibilities:
 * - Inflates Compose overlay view into the session window.
 * - Sets window flags for lock-screen/keyguard visibility.
 * - Coordinates lifecycle and cancellation via AssistantSession.
 */
class LokiVoiceInteractionSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    val assistantSession = AssistantSession(context = context, onDismissCallback = { hide() })

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate()")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Configure session window layout and keyguard behavior
        window?.window?.let { win ->
            val lp = win.attributes
            lp.gravity = Gravity.BOTTOM
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp

            // Ensure session overlay can display over lock screen / keyguard
            @Suppress("DEPRECATION")
            win.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onCreateContentView(): View {
        Log.i(TAG, "onCreateContentView()")
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LokiVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@LokiVoiceInteractionSession)
            setContent {
                LokiTheme {
                    val state by assistantSession.state.collectAsState()
                    val amplitude by assistantSession.amplitude.collectAsState()
                    VoiceSessionOverlay(
                        state = state,
                        amplitude = amplitude,
                        onDismiss = { assistantSession.dismiss() }
                    )
                }
            }
        }
    }

    private var voiceStartCuePlayed: Boolean = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow() showFlags=$showFlags, args=$args")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (audioStartCueEnabled && !voiceStartCuePlayed) {
            AudioCue.playStartTone()
            voiceStartCuePlayed = true
        }
        assistantSession.startTurn()
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "onHide()")
        assistantSession.cancelTurn()
        voiceStartCuePlayed = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy()")
        voiceStartCuePlayed = false
        assistantSession.destroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    companion object {
        private const val TAG = "LokiVoiceInteractionSession"
    }
}

enum class VoiceEqualizerMode {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

@Composable
fun VoiceEqualizer(
    amplitude: Float,
    mode: VoiceEqualizerMode,
    modifier: Modifier = Modifier
) {
    val barCount = 7
    val barColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "equalizer_loop")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "animated_amplitude"
    )

    val barWeights = floatArrayOf(0.45f, 0.7f, 0.95f, 1.0f, 0.95f, 0.7f, 0.45f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val totalBarWidth = 4.dp.toPx()
        val barSpacing = 6.dp.toPx()
        val totalWidth = barCount * totalBarWidth + (barCount - 1) * barSpacing
        val startX = (size.width - totalWidth) / 2f
        val canvasHeight = size.height
        val minHeightRatio = 0.12f

        for (i in 0 until barCount) {
            val heightFraction = when (mode) {
                VoiceEqualizerMode.IDLE -> {
                    minHeightRatio + 0.04f * kotlin.math.sin((i / 6f) * Math.PI).toFloat()
                }
                VoiceEqualizerMode.LISTENING -> {
                    val weight = barWeights[i]
                    val reactiveHeight = minHeightRatio + (animatedAmplitude * weight * (1f - minHeightRatio))
                    reactiveHeight.coerceIn(minHeightRatio, 1f)
                }
                VoiceEqualizerMode.PROCESSING -> {
                    val wave = (kotlin.math.sin(pulsePhase + i * 0.4) + 1.0) / 2.0
                    (minHeightRatio + 0.25f * wave.toFloat()).coerceIn(minHeightRatio, 1f)
                }
                VoiceEqualizerMode.SPEAKING -> {
                    val wave = (kotlin.math.sin(wavePhase + i * (2 * Math.PI / barCount)) + 1.0) / 2.0
                    (minHeightRatio + 0.65f * wave.toFloat()).coerceIn(minHeightRatio, 1f)
                }
            }

            val barHeight = canvasHeight * heightFraction
            val x = startX + i * (totalBarWidth + barSpacing)
            val y = (canvasHeight - barHeight) / 2f

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(totalBarWidth, barHeight),
                cornerRadius = CornerRadius(totalBarWidth / 2f, totalBarWidth / 2f)
            )
        }
    }
}

@Composable
fun VoiceSessionOverlay(
    state: AssistantState,
    amplitude: Float = 0f,
    onDismiss: () -> Unit
) {
    val equalizerMode = when (state) {
        is AssistantState.Listening, is AssistantState.AwaitingFollowUp -> VoiceEqualizerMode.LISTENING
        is AssistantState.Processing -> VoiceEqualizerMode.PROCESSING
        is AssistantState.Speaking -> VoiceEqualizerMode.SPEAKING
        is AssistantState.Idle, is AssistantState.Error, is AssistantState.Completed -> VoiceEqualizerMode.IDLE
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Loki Assistant",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            VoiceEqualizer(
                amplitude = amplitude,
                mode = equalizerMode,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is AssistantState.Idle -> {
                    Text(
                        text = "Ready",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is AssistantState.Listening -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Listening",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        val promptText = when {
                            state.partialTranscript.isNotEmpty() -> state.partialTranscript
                            state.strategy == VoiceInputStrategy.DIRECT_AUDIO -> "Listening (Direct Audio)..."
                            else -> "Listening..."
                        }
                        Text(
                            text = promptText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is AssistantState.AwaitingFollowUp -> {
                    val infiniteTransition = rememberInfiniteTransition(label = "followup_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.7f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_alpha"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Listening",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Listening...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = state.responseText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = pulseAlpha),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
                is AssistantState.Processing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        val processingText = when {
                            state.isDemoted && state.query.isNotEmpty() -> "${state.query} (STT fallback)"
                            state.isDemoted -> "Transcribing with STT fallback..."
                            state.query.isNotEmpty() -> state.query
                            else -> "Thinking..."
                        }
                        Text(
                            text = processingText,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                is AssistantState.Speaking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speaking",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = state.responseText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                is AssistantState.Completed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = state.responseText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                is AssistantState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Dismiss")
            }
        }
    }
}
