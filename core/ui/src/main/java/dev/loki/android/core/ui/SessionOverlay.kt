package dev.loki.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SessionOverlay(
    state: SessionUiState,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF16161E),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚡ Loki Assistant",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is SessionUiState.Idle -> {
                    Text(
                        text = "Ready",
                        fontSize = 15.sp,
                        color = Color(0xFF8E8EA0)
                    )
                }
                is SessionUiState.Listening -> {
                    Text(
                        text = if (state.partialTranscript.isNotEmpty()) state.partialTranscript else "🎙️ Listening...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF818CF8)
                    )
                }
                is SessionUiState.Processing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = if (state.query.isNotEmpty()) state.query else "Thinking...",
                            fontSize = 15.sp,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
                is SessionUiState.Speaking -> {
                    Text(
                        text = "🔊 ${state.responseText}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF34D399)
                    )
                }
                is SessionUiState.Error -> {
                    Text(
                        text = "⚠️ ${state.message}",
                        fontSize = 15.sp,
                        color = Color(0xFFF87171)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Dismiss")
            }
        }
    }
}
