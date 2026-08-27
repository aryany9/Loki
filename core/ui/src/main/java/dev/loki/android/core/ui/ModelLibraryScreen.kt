package dev.loki.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.loki.android.core.llm.ModelAvailability
import dev.loki.android.core.llm.ModelCatalogEntry
import dev.loki.android.core.llm.ModelFormat
import dev.loki.android.core.llm.ModelRecord
import dev.loki.android.core.llm.ModelRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    models: List<ModelRecord>,
    onNavigateBack: () -> Unit,
    onImport: () -> Unit,
    onLoad: (String) -> Unit,
    onEject: () -> Unit,
    onDelete: (String) -> Unit,
    pendingImportName: String? = null,
    onConfirmImport: (String, String, ModelRuntime, ModelFormat) -> Unit = { _, _, _, _ -> },
    onCancelImport: () -> Unit = {},
    catalog: List<ModelCatalogEntry> = emptyList(),
    onDownload: (ModelCatalogEntry) -> Unit = {},
    operationProgress: Float? = null,
    errorMessage: String? = null
) {
    val (name, setName) = remember(pendingImportName) { mutableStateOf(pendingImportName.orEmpty()) }
    val (family, setFamily) = remember { mutableStateOf("") }
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
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmImport(name, family, ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL) },
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
                navigationIcon = { IconButton(onClick = onNavigateBack) { Text("←") } }
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
                            Text("${entry.runtime} · ${entry.format}")
                        }
                        Button(onClick = { onDownload(entry) }) { Text("Download") }
                    }
                }
            }
            if (models.isEmpty()) {
                Text("No installed models", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.id }) { model ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${model.format} · ${model.availability}")
                        }
                        when (model.availability) {
                            ModelAvailability.LOADED -> Button(onClick = onEject) { Text("Eject") }
                            ModelAvailability.DOWNLOADED -> Button(onClick = { onLoad(model.id) }) { Text("Load") }
                            ModelAvailability.NOT_DOWNLOADED -> Unit
                        }
                        Button(onClick = { setPendingDeleteId(model.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
