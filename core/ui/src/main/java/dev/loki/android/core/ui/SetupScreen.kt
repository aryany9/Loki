package dev.loki.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.sp
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.tools.PermissionState

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
        shape = RoundedCornerShape(12.dp),
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
                Text(
                    text = if (isReady) "✅" else "❌",
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = 8.dp)
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
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose / Download", fontSize = 13.sp)
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
 * "Get Started" is only enabled when both [llmReady] and [asrReady] are true.
 *
 * @param permissions      Current permission items for the permissions section.
 * @param models           Live model records from [ModelLibraryManager.manifest].
 * @param catalog          Bundled catalog entries (passed for display context; acquisition
 *                         happens in ModelLibraryScreen).
 * @param llmReady         Whether [ModelRuntime.LITERT_LM] is LOADED and ready.
 * @param asrReady         Whether [ModelRuntime.LITERT_ASR] is LOADED and ready.
 * @param onRequestAllPermissions  Request all runtime permissions.
 * @param onCompleteSetup  Called when the user taps "Get Started" and both runtimes are ready.
 * @param onProvisionRuntime  Called when the user taps "Choose / Download" on a runtime card.
 */
@Composable
fun SetupScreen(
    permissions: List<PermissionItem>,
    models: List<ModelRecord>,
    catalog: List<ModelCatalogEntry>,
    llmReady: Boolean,
    asrReady: Boolean,
    onRequestAllPermissions: () -> Unit,
    onCompleteSetup: () -> Unit,
    onProvisionRuntime: (ModelRuntime) -> Unit
) {
    val audioGranted = permissions.firstOrNull {
        it.permission == android.Manifest.permission.RECORD_AUDIO
    }?.state == PermissionState.GRANTED

    // Derive the display name of the currently loaded model for each runtime
    val loadedLlmName = models.firstOrNull {
        it.runtime == ModelRuntime.LITERT_LM && it.availability == ModelAvailability.LOADED
    }?.displayName
    val loadedAsrName = models.firstOrNull {
        it.runtime == ModelRuntime.LITERT_ASR && it.availability == ModelAvailability.LOADED
    }?.displayName

    val bothModelsReady = llmReady && asrReady

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "⚡ Welcome to Loki",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Private, local-first offline voice assistant powered by on-device AI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Permissions section
                Surface(
                    shape = RoundedCornerShape(16.dp),
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

                RuntimeProvisionCard(
                    title = "ASR / Voice Recognition",
                    description = "On-device Whisper model for offline speech-to-text.",
                    isReady = asrReady,
                    loadedModelName = loadedAsrName,
                    onProvision = { onProvisionRuntime(ModelRuntime.LITERT_ASR) }
                )
            }

            // Action buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!audioGranted) {
                    Button(
                        onClick = onRequestAllPermissions,
                        shape = RoundedCornerShape(16.dp),
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = onCompleteSetup,
                    enabled = bothModelsReady,
                    shape = RoundedCornerShape(16.dp),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
