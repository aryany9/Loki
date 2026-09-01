package dev.loki.android.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import dev.loki.android.core.conversation.MemoryEntry
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.theme.LokiCornerTokens
import dev.loki.android.core.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToModelLibrary: () -> Unit,
    onNavigateToAgentPlayground: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Theme Mode Section
            SettingsSectionHeader(title = "Appearance")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose your preferred interface theme.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val isSelected = currentThemeMode == mode
                            val label = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Language Section
            SettingsSectionHeader(title = "Language & Voice")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Conversation Language",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Controls speech recognition, response language, and voice output. Applies from the next conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var languageDropdownExpanded by remember { mutableStateOf(false) }
                    val currentLangTag by viewModel.conversationLanguage.collectAsState()
                    val selectedOption = CONVERSATION_LANGUAGES.firstOrNull { it.tag.equals(currentLangTag, ignoreCase = true) }
                        ?: LanguageOption(currentLangTag, currentLangTag)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { languageDropdownExpanded = true },
                            shape = RoundedCornerShape(LokiCornerTokens.small),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedOption.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("▼", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        DropdownMenu(
                            expanded = languageDropdownExpanded,
                            onDismissRequest = { languageDropdownExpanded = false }
                        ) {
                            CONVERSATION_LANGUAGES.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.displayName,
                                            fontWeight = if (option.tag.equals(currentLangTag, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        viewModel.setConversationLanguage(option.tag)
                                        languageDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Current Model Section
            SettingsSectionHeader(title = "On-Device Intelligence")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Current Model State",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val statusText = when (modelState) {
                                is LlmModelState.Ready -> "Ready (${(modelState as LlmModelState.Ready).modelName})"
                                is LlmModelState.Loading -> "Loading on-device model..."
                                is LlmModelState.Error -> "Error: ${(modelState as LlmModelState.Error).message}"
                                is LlmModelState.NotLoaded -> "Not Loaded"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (modelState) {
                                    is LlmModelState.Ready -> MaterialTheme.colorScheme.tertiary
                                    is LlmModelState.Error -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        if (modelState is LlmModelState.Error) {
                            Button(
                                onClick = { viewModel.retryLoadModel() },
                                shape = RoundedCornerShape(LokiCornerTokens.small)
                            ) {
                                Text("Retry")
                            }
                        } else if (modelState is LlmModelState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsNavRow(
                        icon = Icons.Default.Apps,
                        title = "Model Library",
                        subtitle = "Download, import, and switch LLM/ASR models",
                        onClick = onNavigateToModelLibrary
                    )
                }
            }

            // Shortcuts & Tools Section
            SettingsSectionHeader(title = "Configuration & Permissions")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsNavRow(
                        icon = Icons.Default.Build,
                        title = "Agent Playground",
                        subtitle = "Configure system prompts, temperature, and agent parameters",
                        onClick = onNavigateToAgentPlayground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsNavRow(
                        icon = Icons.Default.Security,
                        title = "App Permissions",
                        subtitle = "Manage microphone, contacts, and phone call access",
                        onClick = onNavigateToPermissions
                    )
                }
            }

            // Memory Section
            SettingsSectionHeader(title = "Memory")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val memories by viewModel.memories.collectAsState()
                    var newMemoryText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                    var memoryToEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<MemoryEntry?>(null) }
                    var showClearConfirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "What Loki remembers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Loki uses these in every new chat.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (memories.isNotEmpty()) {
                            androidx.compose.material3.TextButton(
                                onClick = { showClearConfirm = true }
                            ) {
                                Text(
                                    text = "Clear all",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Add memory input field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            placeholder = {
                                Text(
                                    text = "Add a fact (e.g. My name is Arya)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(LokiCornerTokens.small),
                            modifier = Modifier.weight(1f)
                        )

                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                if (newMemoryText.isNotBlank()) {
                                    viewModel.addMemory(newMemoryText)
                                    newMemoryText = ""
                                }
                            },
                            enabled = newMemoryText.isNotBlank(),
                            shape = RoundedCornerShape(LokiCornerTokens.small)
                        ) {
                            Text("Add")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (memories.isEmpty()) {
                        Text(
                            text = "Nothing remembered yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            memories.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "• ${entry.text}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = { memoryToEdit = entry },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                                            contentDescription = "Edit memory",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteMemory(entry.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                            contentDescription = "Delete memory",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Edit memory dialog
                    memoryToEdit?.let { entry ->
                        var editText by androidx.compose.runtime.remember(entry) {
                            androidx.compose.runtime.mutableStateOf(entry.text)
                        }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { memoryToEdit = null },
                            title = { Text("Edit Memory") },
                            text = {
                                androidx.compose.material3.OutlinedTextField(
                                    value = editText,
                                    onValueChange = { editText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(LokiCornerTokens.small)
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (editText.isNotBlank()) {
                                            viewModel.updateMemory(entry.id, editText)
                                        }
                                        memoryToEdit = null
                                    },
                                    enabled = editText.isNotBlank()
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { memoryToEdit = null }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    // Clear all confirm dialog
                    if (showClearConfirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showClearConfirm = false },
                            title = { Text("Clear all memories?") },
                            text = { Text("This will permanently remove all stored facts. New chats will no longer have access to this information.") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.clearMemories()
                                        showClearConfirm = false
                                    },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Clear All")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { showClearConfirm = false }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }

            // About Section
            SettingsSectionHeader(title = "About Loki")
            Card(
                shape = RoundedCornerShape(LokiCornerTokens.medium),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚡ Loki",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "100% Private, On-Device AI Assistant running entirely offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Version 1.0.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(24.dp)
                .padding(end = 4.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
