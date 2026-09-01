package dev.loki.android.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.theme.LokiCornerTokens
import kotlinx.coroutines.launch

enum class ComposerActionState {
    STOP_GENERATION,
    STOP_RECORDING,
    SEND,
    MIC
}

private data class ActionButtonConfig(
    val backgroundColor: Color,
    val icon: ImageVector,
    val iconColor: Color,
    val contentDescription: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToPermissions: (() -> Unit)? = null,
    onNavigateToModelLibrary: (() -> Unit)? = null,
    onNavigateToAgentPlayground: (() -> Unit)? = null,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val messages by viewModel.messages.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    val isGenerating by remember(messages) {
        derivedStateOf {
            val lastMsg = messages.lastOrNull()
            lastMsg?.sender == MessageSender.ASSISTANT && (lastMsg.isStreaming || lastMsg.isThinking)
        }
    }

    val actionState = when {
        isGenerating -> ComposerActionState.STOP_GENERATION
        isRecording -> ComposerActionState.STOP_RECORDING
        inputText.isNotBlank() -> ComposerActionState.SEND
        else -> ComposerActionState.MIC
    }

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= totalItems - 2
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerShape = RoundedCornerShape(topEnd = LokiCornerTokens.large, bottomEnd = LokiCornerTokens.large),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp)
                ) {
                    // Drawer Header
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = "⚡ Loki",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "On-Device AI Assistant",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    // New Chat
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Add, contentDescription = "New chat") },
                        label = { Text("New chat", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) },
                        selected = false,
                        onClick = {
                            viewModel.newConversation()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Recents Section
                    Text(
                        text = "Recent Chats",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val recents = conversations.take(20)
                        items(recents, key = { it.id }) { conv ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                label = {
                                    Text(
                                        text = conv.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                badge = {
                                    IconButton(
                                        onClick = { viewModel.deleteConversation(conv.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                                    }
                                },
                                selected = false,
                                onClick = {
                                    viewModel.selectConversation(conv.id)
                                    coroutineScope.launch { drawerState.close() }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Destinations
                    if (onNavigateToModelLibrary != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                            label = { Text("Model Library", style = MaterialTheme.typography.labelLarge) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigateToModelLibrary()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    if (onNavigateToAgentPlayground != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Build, contentDescription = null) },
                            label = { Text("Agent Playground", style = MaterialTheme.typography.labelLarge) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigateToAgentPlayground()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    if (onNavigateToPermissions != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Security, contentDescription = null) },
                            label = { Text("Permissions", style = MaterialTheme.typography.labelLarge) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigateToPermissions()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    if (onNavigateToSettings != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings", style = MaterialTheme.typography.labelLarge) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigateToSettings()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    title = {
                        Text(
                            text = "⚡ Loki",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    actions = {
                        ModelStatusBadge(
                            modelState = modelState,
                            onRetry = { viewModel.retryLoadModel() },
                            onNavigateToModelLibrary = onNavigateToModelLibrary
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
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
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Loading on-device LLM (Qwen 4B)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else if (modelState is LlmModelState.Error) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.retryLoadModel() },
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "⚠️ ${(modelState as LlmModelState.Error).message} (Tap to retry)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (messages.isEmpty()) {
                    HomeGreetingState(
                        onSuggestionClick = { suggestion ->
                            viewModel.sendMessage(suggestion)
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            when (msg.sender) {
                                MessageSender.USER -> UserMessageBubble(message = msg)
                                MessageSender.ASSISTANT -> AssistantMessage(message = msg)
                            }
                        }
                    }
                }

                // Voice Error Banner
                AnimatedVisibility(
                    visible = voiceError != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    voiceError?.let { errorMsg ->
                        val isDownloadingVoiceModel by viewModel.isDownloadingVoiceModel.collectAsState()
                        val isVoiceModelDownloadable by viewModel.isVoiceModelDownloadable.collectAsState()
                        val voiceDownloadProgress by viewModel.voiceDownloadProgress.collectAsState()

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(LokiCornerTokens.medium),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isDownloadingVoiceModel) Icons.Default.CloudDownload else Icons.Default.WarningAmber,
                                        contentDescription = if (isDownloadingVoiceModel) "Downloading voice model" else "Voice input warning",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isDownloadingVoiceModel && voiceDownloadProgress != null && voiceDownloadProgress!! >= 0f) {
                                            "$errorMsg (${(voiceDownloadProgress!! * 100).toInt()}%)"
                                        } else {
                                            errorMsg
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                if (isDownloadingVoiceModel) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else if (isVoiceModelDownloadable) {
                                    TextButton(
                                        onClick = { viewModel.downloadVoiceModel() },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = "Download",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.dismissVoiceError() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Confirmation Card (gated destructive action)
                AnimatedVisibility(
                    visible = pendingConfirmation != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    pendingConfirmation?.let { confirm ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(LokiCornerTokens.medium),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = "Confirmation required",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Confirmation Required",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Text(
                                    text = confirm.repeatBack.ifEmpty { "Do you want to proceed with ${confirm.toolName}?" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { viewModel.respondToConfirmation(false) }
                                    ) {
                                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { viewModel.respondToConfirmation(true) },
                                        shape = RoundedCornerShape(LokiCornerTokens.small),
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text("Confirm")
                                    }
                                }
                            }
                        }
                    }
                }

                // Modern Floating Pill Composer
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Borderless Pill Input Container
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 160.dp),
                            shape = RoundedCornerShape(LokiCornerTokens.inputBar),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        if (inputText.isEmpty()) {
                                            Text(
                                                text = "Ask Loki anything...",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Morphing Action Button
                        AnimatedContent(
                            targetState = actionState,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200, delayMillis = 50)) +
                                 scaleIn(initialScale = 0.8f, animationSpec = tween(200, delayMillis = 50)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(150)) +
                                        scaleOut(targetScale = 0.8f, animationSpec = tween(150))
                                    )
                            },
                            label = "composerActionButton"
                        ) { state ->
                            val config = when (state) {
                                ComposerActionState.STOP_GENERATION -> ActionButtonConfig(
                                    backgroundColor = MaterialTheme.colorScheme.primary,
                                    icon = Icons.Default.Stop,
                                    iconColor = MaterialTheme.colorScheme.onPrimary,
                                    contentDescription = "Stop Generation",
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.cancelGeneration()
                                    }
                                )
                                ComposerActionState.STOP_RECORDING -> ActionButtonConfig(
                                    backgroundColor = MaterialTheme.colorScheme.error,
                                    icon = Icons.Default.Stop,
                                    iconColor = MaterialTheme.colorScheme.onError,
                                    contentDescription = "Stop Recording",
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.stopVoiceInput()
                                    }
                                )
                                ComposerActionState.SEND -> ActionButtonConfig(
                                    backgroundColor = MaterialTheme.colorScheme.primary,
                                    icon = Icons.AutoMirrored.Filled.Send,
                                    iconColor = MaterialTheme.colorScheme.onPrimary,
                                    contentDescription = "Send Message",
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.sendMessage(inputText)
                                            inputText = ""
                                        }
                                    }
                                )
                                ComposerActionState.MIC -> ActionButtonConfig(
                                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                    icon = Icons.Default.Mic,
                                    iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    contentDescription = "Voice Input",
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.startVoiceInput()
                                    }
                                )
                            }

                            IconButton(
                                onClick = config.onClick,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(config.backgroundColor, CircleShape)
                            ) {
                                Icon(
                                    imageVector = config.icon,
                                    contentDescription = config.contentDescription,
                                    tint = config.iconColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeGreetingState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val suggestions = remember {
        listOf(
            "What can you do?",
            "Turn on Bluetooth",
            "Tell me a joke",
            "Summarize my last chat"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Hi there ✨",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "What would you like to do today?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Suggestions",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSuggestionClick(suggestion)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelStatusBadge(
    modelState: LlmModelState,
    onRetry: () -> Unit,
    onNavigateToModelLibrary: (() -> Unit)? = null
) {
    var showPopover by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = RoundedCornerShape(LokiCornerTokens.medium),
            color = when (modelState) {
                is LlmModelState.Ready -> MaterialTheme.colorScheme.primaryContainer
                is LlmModelState.Loading -> MaterialTheme.colorScheme.secondaryContainer
                is LlmModelState.Error -> MaterialTheme.colorScheme.errorContainer
                is LlmModelState.NotLoaded -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .padding(end = 8.dp)
                .clickable { showPopover = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (modelState) {
                    is LlmModelState.Ready -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        )
                        Text("Ready", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    is LlmModelState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text("Loading...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    is LlmModelState.Error -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                        Text("Error", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    is LlmModelState.NotLoaded -> {
                        Text("Not Loaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showPopover,
            onDismissRequest = { showPopover = false },
            modifier = Modifier
                .width(260.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(LokiCornerTokens.medium))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "On-Device Model",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val modelName = when (modelState) {
                    is LlmModelState.Ready -> (modelState as LlmModelState.Ready).modelName
                    is LlmModelState.Loading -> "Qwen 2.5 / LiteRT"
                    is LlmModelState.Error -> "Model Error"
                    is LlmModelState.NotLoaded -> "None Loaded"
                }
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val stateDescription = when (modelState) {
                    is LlmModelState.Ready -> "Model is active and ready for offline inference."
                    is LlmModelState.Loading -> "Initializing weights and KV cache..."
                    is LlmModelState.Error -> (modelState as LlmModelState.Error).message
                    is LlmModelState.NotLoaded -> "No model loaded. Visit Model Library."
                }
                Text(
                    text = stateDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (modelState is LlmModelState.Error) {
                    Button(
                        onClick = {
                            showPopover = false
                            onRetry()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(LokiCornerTokens.small)
                    ) {
                        Text("Retry Initialization")
                    }
                }

                if (onNavigateToModelLibrary != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(
                        onClick = {
                            showPopover = false
                            onNavigateToModelLibrary()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manage Models in Library")
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = LokiCornerTokens.messageBubble,
                topEnd = LokiCornerTokens.messageBubble,
                bottomStart = LokiCornerTokens.messageBubble,
                bottomEnd = LokiCornerTokens.messageBubbleCornerSmall
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(start = 48.dp, end = 4.dp)
        ) {
            Text(
                text = message.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun AssistantMessage(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (message.isThinking) {
            ThinkingIndicator()
        } else {
            if (message.text.isNotEmpty()) {
                Markdown(
                    content = message.text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (message.toolResult != null) {
            Spacer(modifier = Modifier.height(8.dp))
            ToolResultCard(
                toolResult = message.toolResult,
                toolName = message.toolName
            )
        }
    }
}

@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer(alpha = alpha1)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer(alpha = alpha2)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer(alpha = alpha3)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ToolResultCard(
    toolResult: ToolResult,
    toolName: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val isSuccess = toolResult.success
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val icon = if (isSuccess) Icons.Default.Check else Icons.Default.Close
    val iconColor = if (isSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(LokiCornerTokens.medium),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = if (isSuccess) "Success" else "Error",
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val title = toolName ?: "Tool Execution"
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    val payloadText = if (isSuccess) {
                        toolResult.data?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }
                            ?.ifEmpty { "Success (no output)" }
                            ?: "Success (no output)"
                    } else {
                        toolResult.error ?: "Action failed"
                    }
                    Surface(
                        shape = RoundedCornerShape(LokiCornerTokens.small),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = payloadText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
