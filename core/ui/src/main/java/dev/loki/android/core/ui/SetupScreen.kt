package dev.loki.android.core.ui

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.tools.PermissionState
import dev.loki.android.core.theme.LokiCornerTokens

/**
 * A card that shows the current state of a single mandatory runtime and provides
 * a CTA to acquire/load it when it is not yet ready.
 */
@Composable
private fun RuntimeProvisionCard(
    title: String,
    description: String,
    isReady: Boolean,
    loadedModelName: String?,
    onProvision: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(LokiCornerTokens.medium),
        color = if (isReady) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = if (isReady) "Ready" else "Not Ready",
                    tint = if (isReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isReady && loadedModelName != null) {
                Text(
                    text = "Loaded: $loadedModelName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Required — not yet loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onProvision,
                    shape = RoundedCornerShape(LokiCornerTokens.small),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose / Download", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Setup provisioning screen — the first-run gatekeeper.
 *
 * Shows one [RuntimeProvisionCard] per mandatory runtime ([ModelRuntime.LITERT_LM] and
 * [ModelRuntime.LITERT_ASR]). Each card reflects live state from [models] and provides a
 * CTA to navigate into the Model Library when the runtime is not yet loaded.
 *
 * [onCompleteSetup] is enabled only when both mandatory runtimes have a loaded model.
 */
@Composable
fun SetupScreen(
    permissions: List<PermissionItem>,
    models: List<ModelRecord>,
    catalog: List<ModelCatalogEntry> = emptyList(),
    llmReady: Boolean = false,
    asrReady: Boolean = false,
    onRequestAllPermissions: () -> Unit,
    onCompleteSetup: () -> Unit,
    onProvisionRuntime: (ModelRuntime) -> Unit,
    onNavigateToAgentPlayground: (() -> Unit)? = null
) {
    val audioGranted = permissions.firstOrNull { it.permission == android.Manifest.permission.RECORD_AUDIO }?.state == PermissionState.GRANTED

    val loadedLlm = models.firstOrNull { it.runtime == ModelRuntime.LITERT_LM && it.availability == ModelAvailability.LOADED }
    val loadedLlmName = loadedLlm?.displayName
    val isAudioCapable = loadedLlm?.capabilities?.isAudioInputSupported == true

    val loadedAsrName = models.firstOrNull { it.runtime == ModelRuntime.LITERT_ASR && it.availability == ModelAvailability.LOADED }?.displayName

    val bothModelsReady = llmReady && (isAudioCapable || asrReady)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "⚡",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Welcome to Loki",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Your private, on-device AI voice assistant. All processing stays 100% on your phone — no cloud, no tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Permissions section
                Surface(
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Microphone access is required so Loki can listen to voice commands offline using on-device Whisper speech recognition.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Contacts & Phone permissions enable commands like 'Call Mom' or 'Search Rahul'. You can also grant them later in settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Required AI Models section
                Text(
                    text = "Required AI Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                RuntimeProvisionCard(
                    title = "LLM / Reasoning",
                    description = "On-device language model for understanding and responding.",
                    isReady = llmReady,
                    loadedModelName = loadedLlmName,
                    onProvision = { onProvisionRuntime(ModelRuntime.LITERT_LM) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                val asrDesc = if (isAudioCapable) {
                    "Optional · Active LLM supports direct audio input."
                } else {
                    "On-device Whisper model for offline speech-to-text."
                }
                RuntimeProvisionCard(
                    title = "ASR / Voice Recognition",
                    description = asrDesc,
                    isReady = asrReady || isAudioCapable,
                    loadedModelName = if (isAudioCapable && loadedAsrName == null) "Direct Audio Active" else loadedAsrName,
                    onProvision = { onProvisionRuntime(ModelRuntime.LITERT_ASR) }
                )

                if (onNavigateToAgentPlayground != null && bothModelsReady) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onNavigateToAgentPlayground,
                        shape = RoundedCornerShape(LokiCornerTokens.medium),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tune Assistant & Playground", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Action buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!audioGranted) {
                    Button(
                        onClick = onRequestAllPermissions,
                        shape = RoundedCornerShape(LokiCornerTokens.medium),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = "Grant Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = onCompleteSetup,
                    enabled = bothModelsReady,
                    shape = RoundedCornerShape(LokiCornerTokens.medium),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (bothModelsReady) "Get Started" else "Set Up Models to Continue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
