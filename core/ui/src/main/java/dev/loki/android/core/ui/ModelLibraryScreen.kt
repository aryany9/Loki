package dev.loki.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    models: List<ModelRecord>,
    onNavigateBack: () -> Unit,
    onImport: () -> Unit,
    onLoad: (String) -> Unit,
    onEject: (ModelRuntime) -> Unit,
    onDelete: (String) -> Unit,
    pendingImportName: String? = null,
    onConfirmImport: (String, String, ModelRuntime, ModelFormat, Boolean) -> Unit = { _, _, _, _, _ -> },
    onCancelImport: () -> Unit = {},
    catalog: List<ModelCatalogEntry> = emptyList(),
    onDownload: (ModelCatalogEntry) -> Unit = {},
    operationProgress: Float? = null,
    errorMessage: String? = null
) {
    val (name, setName) = remember(pendingImportName) { mutableStateOf(pendingImportName.orEmpty()) }
    val (family, setFamily) = remember { mutableStateOf("") }
    val (supportsAudio, setSupportsAudio) = remember(pendingImportName) { mutableStateOf(false) }
    val (pendingDeleteId, setPendingDeleteId) = remember { mutableStateOf<String?>(null) }

    if (pendingImportName != null) {
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("Confirm model metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = setName, label = { Text("Model name") })
                    OutlinedTextField(value = family, onValueChange = setFamily, label = { Text("Model family (optional)") })
                    Text("Format: .litertlm (LiteRT-LM)")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = supportsAudio,
                            onCheckedChange = setSupportsAudio
                        )
                        Text("Supports direct audio input (e.g. Gemma 4 E4B)")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmImport(name, family, ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL, supportsAudio) },
                    enabled = name.isNotBlank()
                ) {
                    Text("Validate and add")
                }
            },
            dismissButton = { Button(onClick = onCancelImport) { Text("Cancel") } }
        )
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { setPendingDeleteId(null) },
            title = { Text("Delete model?") },
            text = { Text("This removes the model file from Loki storage.") },
            confirmButton = {
                Button(onClick = {
                    onDelete(pendingDeleteId)
                    setPendingDeleteId(null)
                }) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { setPendingDeleteId(null) }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Library") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import .litertlm model") }
            if (operationProgress != null) {
                LinearProgressIndicator(
                    progress = { operationProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
            if (catalog.isNotEmpty()) {
                Text("Available models", style = MaterialTheme.typography.titleMedium)
                catalog.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.displayName)
                            val audioTag = if (entry.capabilities.any { it.equals("audio-input", ignoreCase = true) }) " · Direct Audio" else ""
                            Text("${entry.runtime} · ${entry.format}$audioTag", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onDownload(entry) }) { Text("Download") }
                    }
                }
            }
            if (models.isEmpty()) {
                Text("No installed models", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val probe = remember(context) { dev.loki.android.core.llm.NpuCapabilityProbe.probe(context) }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.id }) { model ->
                    val targetSoc = model.capabilities.npuTargetSoc.value
                    val socModel = probe.socModel
                    val htpGen = probe.htpGeneration
                    val isSocCompatible = if (model.capabilities.isNpuTargeted && !targetSoc.isNullOrBlank()) {
                        (socModel != null && socModel.contains(targetSoc, ignoreCase = true)) ||
                        (htpGen != null && htpGen.equals(dev.loki.android.core.llm.NpuCapabilityProbe.lookupHtpGeneration(targetSoc), ignoreCase = true))
                    } else true

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                            val audioTag = if (model.capabilities.isAudioInputSupported) " · Direct Audio" else ""
                            val npuTag = if (model.capabilities.isNpuTargeted) " · NPU (${targetSoc ?: "Qualcomm"})" else ""
                            if (model.capabilities.isNpuTargeted && !isSocCompatible) {
                                Text(
                                    "Unavailable for execution on this device (targets $targetSoc; device is ${probe.socModel ?: "unsupported"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("${model.format} · ${model.availability}$audioTag$npuTag", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        when (model.availability) {
                            ModelAvailability.LOADED -> Button(onClick = { onEject(model.runtime) }) { Text("Eject") }
                            ModelAvailability.DOWNLOADED -> {
                                if (isSocCompatible) {
                                    Button(onClick = { onLoad(model.id) }) { Text("Load") }
                                } else {
                                    Button(onClick = {}, enabled = false) { Text("Unavailable") }
                                }
                            }
                            ModelAvailability.NOT_DOWNLOADED -> Unit
                        }
                        Button(onClick = { setPendingDeleteId(model.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
