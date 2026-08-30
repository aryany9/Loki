package dev.loki.android.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LokiCornerTokens {
    val extraSmall: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val extraLarge: Dp = 28.dp

    val messageBubble: Dp = 16.dp
    val messageBubbleLarge: Dp = 20.dp
    val messageBubbleCornerSmall: Dp = 4.dp
    val inputBar: Dp = 24.dp
    val card: Dp = 16.dp
    val chip: Dp = 8.dp
    val badge: Dp = 6.dp
    val dialog: Dp = 28.dp
}

val LokiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
