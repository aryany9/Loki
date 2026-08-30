package dev.loki.android.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LokiSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    val extraSmall: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val extraLarge: Dp = 24.dp
    val extraExtraLarge: Dp = 32.dp
}

object LokiTokens {
    val spacing = LokiSpacing
    val corners = LokiCornerTokens
}
