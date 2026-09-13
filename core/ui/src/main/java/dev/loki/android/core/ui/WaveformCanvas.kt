package dev.loki.android.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
fun WaveformCanvas(
    waveformData: ByteArray?,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    if (waveformData == null || waveformData.isEmpty()) {
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val barCount = waveformData.size
        val gap = 2.dp.toPx()
        val barWidth = (size.width - (gap * (barCount - 1))) / barCount
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val amp = waveformData[i].toInt().coerceIn(0, 100)
            val normalizedAmp = amp / 100f
            val barHeight = (maxHeight * normalizedAmp).coerceAtLeast(4.dp.toPx())
            
            val x = i * (barWidth + gap) + barWidth / 2f
            val yStart = (maxHeight - barHeight) / 2f
            val yEnd = yStart + barHeight

            val isPlayed = (i.toFloat() / barCount) <= progress
            val color = if (isPlayed) activeColor else inactiveColor

            drawLine(
                color = color,
                start = Offset(x, yStart),
                end = Offset(x, yEnd),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
