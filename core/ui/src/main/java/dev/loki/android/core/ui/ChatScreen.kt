package dev.loki.android.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loki.android.core.llm.LlmModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val messages by viewModel.messages.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Loki", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Spacer(modifier = Modifier.weight(1f))

                        // Model Status Badge
                        ModelStatusBadge(
                            modelState = modelState,
                            onRetry = { viewModel.retryLoadModel() }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121218))
            )
        },
        containerColor = Color(0xFF0D0D12)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // Model loading banner
            if (modelState is LlmModelState.Loading) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1E1B4B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF818CF8)
                        )
                        Text(
                            text = "Loading on-device LLM (Qwen 4B)...",
                            fontSize = 13.sp,
                            color = Color(0xFFC7D2FE)
                        )
                    }
                }
            } else if (modelState is LlmModelState.Error) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.retryLoadModel() },
                    color = Color(0xFF450A0A)
                ) {
                    Text(
                        text = "⚠️ ${(modelState as LlmModelState.Error).message} (Tap to retry)",
                        fontSize = 13.sp,
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(message = msg)
                }
            }

            // Input Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF16161E),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask Loki anything...", color = Color(0xFF6B7280)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF22222E),
                            unfocusedContainerColor = Color(0xFF22222E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Mic Button
                    IconButton(
                        onClick = {
                            if (isRecording) viewModel.stopVoiceInput() else viewModel.startVoiceInput()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = if (isRecording) Color(0xFFEF4444) else Color(0xFF374151),
                                shape = CircleShape
                            )
                    ) {
                        Text(if (isRecording) "⏹" else "🎙️", fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Send Button
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF6366F1), shape = CircleShape)
                    ) {
                        Text("➤", fontSize = 16.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun ModelStatusBadge(
    modelState: LlmModelState,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when (modelState) {
            is LlmModelState.Ready -> Color(0xFF064E3B)
            is LlmModelState.Loading -> Color(0xFF312E81)
            is LlmModelState.Error -> Color(0xFF7F1D1D)
            is LlmModelState.NotLoaded -> Color(0xFF374151)
        },
        modifier = Modifier
            .padding(end = 8.dp)
            .clickable(enabled = modelState is LlmModelState.Error, onClick = onRetry)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (modelState) {
                is LlmModelState.Ready -> {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF10B981), CircleShape))
                    Text("Ready (Qwen 4B)", fontSize = 11.sp, color = Color(0xFF6EE7B7))
                }
                is LlmModelState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(8.dp), strokeWidth = 1.5.dp, color = Color(0xFF818CF8))
                    Text("Loading...", fontSize = 11.sp, color = Color(0xFFA5B4FC))
                }
                is LlmModelState.Error -> {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFFEF4444), CircleShape))
                    Text("Model Error", fontSize = 11.sp, color = Color(0xFFFCA5A5))
                }
                is LlmModelState.NotLoaded -> {
                    Text("Not Loaded", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) Color(0xFF6366F1) else Color(0xFF1E1E2A)
    val textColor = Color.White

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bg,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.isThinking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                        Text(message.text, fontSize = 14.sp, color = Color(0xFF38BDF8))
                    }
                } else {
                    Text(
                        text = message.text,
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )
                }

                if (message.toolResult != null && message.toolResult.data != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF121218),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "✓ Action executed",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
