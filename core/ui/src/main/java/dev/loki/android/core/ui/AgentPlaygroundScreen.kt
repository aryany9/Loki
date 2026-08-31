package dev.loki.android.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.theme.LokiCornerTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentPlaygroundScreen(
    viewModel: AgentPlaygroundViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToModelLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    if (state.showContextResetDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissContextResetDialog() },
            title = { Text("Restart Active Conversation?") },
            text = {
                Text("Saving new inference settings updates native KV cache parameters and will restart any active chat session.")
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmSave() }) {
                    Text("Apply & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissContextResetDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model & Agent Configuration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetDefaults() }) {
                        Text("Reset")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetDefaults() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset Defaults")
                    }
                    Button(
                        onClick = { viewModel.requestSave() },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save Configuration")
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status or Validation Messages
            if (state.validationError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.validationError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (state.statusMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.statusMessage ?: "",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 1. MODEL SECTION
            item {
                Card(
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ACTIVE MODEL",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            // Loaded status badge
                            val isLoaded = state.modelState is LlmModelState.Ready
                            val statusText = when (state.modelState) {
                                is LlmModelState.Ready -> "LOADED"
                                is LlmModelState.Loading -> "LOADING"
                                is LlmModelState.Error -> "ERROR"
                                LlmModelState.NotLoaded -> "NOT LOADED"
                            }
                            val statusColor = if (isLoaded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.selectedModel?.displayName ?: "LiteRT-LM Default Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(10.dp))
                        // Capabilities tags
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CapabilityBadge("Text", state.modelCapabilities.supportsText)
                            CapabilityBadge("Tool Calling", state.modelCapabilities.supportsToolCalling)
                            CapabilityBadge("Audio Input", state.modelCapabilities.supportsAudioInput)
                            CapabilityBadge("Vision", state.modelCapabilities.supportsVisionInput)
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onNavigateToModelLibrary,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Change Model →")
                        }
                    }
                }
            }

            // 2. AGENT · SYSTEM PROMPT SECTION
            item {
                Column {
                    Text(
                        text = "AGENT · SYSTEM PROMPT",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Custom instructions layer on top of Loki's built-in privacy and safety guardrails.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("Enter custom agent instructions or persona...") },
                        maxLines = 6
                    )
                }
            }

            // 3. RESPONSE BEHAVIOR PRESETS
            item {
                Column {
                    Text(
                        text = "RESPONSE BEHAVIOR",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Preset mappings for sampling parameters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresetChip(
                            label = "Fast",
                            selected = state.preset == ResponseBehaviorPreset.FAST,
                            onClick = { viewModel.selectPreset(ResponseBehaviorPreset.FAST) },
                            modifier = Modifier.weight(1f)
                        )
                        PresetChip(
                            label = "Balanced",
                            selected = state.preset == ResponseBehaviorPreset.BALANCED,
                            onClick = { viewModel.selectPreset(ResponseBehaviorPreset.BALANCED) },
                            modifier = Modifier.weight(1f)
                        )
                        PresetChip(
                            label = "Precise",
                            selected = state.preset == ResponseBehaviorPreset.PRECISE,
                            onClick = { viewModel.selectPreset(ResponseBehaviorPreset.PRECISE) },
                            modifier = Modifier.weight(1f)
                        )
                        if (state.preset == ResponseBehaviorPreset.CUSTOM) {
                            PresetChip(
                                label = "Custom",
                                selected = true,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 4. TEST PROMPT SECTION
            item {
                Card(
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "TEST PROMPT",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = state.testPromptInput,
                            onValueChange = { viewModel.updateTestPromptInput(it) },
                            label = { Text("Prompt for model") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { viewModel.runTestPrompt() },
                            enabled = !state.isTestRunning && state.testPromptInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isTestRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Generating...")
                            } else {
                                Text("Run Test Generation")
                            }
                        }

                        if (state.testPromptOutput != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Model Output:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = state.testPromptOutput ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        // Test output display
                        if (state.testToolDiagnostics.isNotEmpty() || state.testError != null) {
                            Spacer(Modifier.height(12.dp))
                            androidx.compose.material3.HorizontalDivider()
                            Spacer(Modifier.height(8.dp))

                            if (state.testError != null) {
                                Text(
                                    text = "Error: ${state.testError}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            if (state.testToolDiagnostics.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Diagnostics:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                state.testToolDiagnostics.forEach { diag ->
                                    Text(
                                        text = "• $diag",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. ADVANCED SECTION (COLLAPSIBLE)
            item {
                Card(
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleAdvancedExpanded() }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (state.isAdvancedExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "ADVANCED PARAMETERS",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (state.isAdvancedExpanded) "Hide" else "Show",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = state.isAdvancedExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Temperature Slider
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Temperature", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            String.format("%.2f", state.temperature),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Slider(
                                        value = state.temperature,
                                        onValueChange = { viewModel.updateTemperature(it) },
                                        valueRange = 0.0f..2.0f,
                                        steps = 40
                                    )
                                }

                                // Top-K Slider
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Top-K", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${state.topK}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Slider(
                                        value = state.topK.toFloat(),
                                        onValueChange = { viewModel.updateTopK(it.toInt()) },
                                        valueRange = 1f..100f,
                                        steps = 99
                                    )
                                }

                                // Top-P Slider
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Top-P", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            String.format("%.2f", state.topP),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Slider(
                                        value = state.topP,
                                        onValueChange = { viewModel.updateTopP(it) },
                                        valueRange = 0.0f..1.0f,
                                        steps = 20
                                    )
                                }

                                // Seed (Optional)
                                OutlinedTextField(
                                    value = state.seed?.toString() ?: "",
                                    onValueChange = { viewModel.updateSeed(it.toIntOrNull()) },
                                    label = { Text("Seed (Optional, integer)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                // Max Output Tokens (Optional)
                                OutlinedTextField(
                                    value = state.maxOutputTokens?.toString() ?: "",
                                    onValueChange = { viewModel.updateMaxOutputTokens(it.toIntOrNull()) },
                                    label = { Text("Max Output Tokens per Turn (Optional)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                // Execution Backend
                                Column {
                                    Text(
                                        text = "Execution Backend",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = state.backend == ExecutionBackend.AUTOMATIC,
                                            onClick = { viewModel.updateBackend(ExecutionBackend.AUTOMATIC) }
                                        )
                                        Text("Auto", modifier = Modifier.clickable { viewModel.updateBackend(ExecutionBackend.AUTOMATIC) })
                                        Spacer(Modifier.width(12.dp))
                                        RadioButton(
                                            selected = state.backend == ExecutionBackend.GPU,
                                            onClick = { viewModel.updateBackend(ExecutionBackend.GPU) }
                                        )
                                        Text("GPU", modifier = Modifier.clickable { viewModel.updateBackend(ExecutionBackend.GPU) })
                                        Spacer(Modifier.width(12.dp))
                                        RadioButton(
                                            selected = state.backend == ExecutionBackend.CPU,
                                            onClick = { viewModel.updateBackend(ExecutionBackend.CPU) }
                                        )
                                        Text("CPU", modifier = Modifier.clickable { viewModel.updateBackend(ExecutionBackend.CPU) })
                                    }
                                }

                                // KV Cache Capacity
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("KV Cache Capacity", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${state.contextKvCapacity ?: 8192} tokens",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Slider(
                                        value = (state.contextKvCapacity ?: 8192).toFloat(),
                                        onValueChange = { viewModel.updateKvCapacity(it.toInt()) },
                                        valueRange = 1024f..16384f,
                                        steps = 15
                                    )
                                    Text(
                                        text = "Runtime memory buffer allocation. Changes force engine re-initialization.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier
    )
}

@Composable
private fun CapabilityBadge(
    name: String,
    supported: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (supported) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (supported) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Surface(
        shape = RoundedCornerShape(LokiCornerTokens.badge),
        color = bg,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = if (supported) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = name,
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (supported) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
