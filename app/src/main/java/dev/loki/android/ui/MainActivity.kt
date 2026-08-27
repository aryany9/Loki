package dev.loki.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.ModelLibraryManager
import dev.loki.android.core.llm.GgufModelDetector
import dev.loki.android.core.llm.ModelAvailability
import dev.loki.android.core.llm.ModelFormat
import dev.loki.android.core.llm.ModelImporter
import dev.loki.android.core.llm.ModelMetadataField
import dev.loki.android.core.llm.ModelRuntime
import dev.loki.android.core.llm.ModelSource
import dev.loki.android.core.llm.ModelRecord
import dev.loki.android.core.llm.ModelDetection
import dev.loki.android.core.llm.ModelValidator
import dev.loki.android.core.llm.LiteRtModelValidator
import dev.loki.android.core.llm.GgufModelValidator
import dev.loki.android.core.llm.ValidationResult
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.PermissionState
import dev.loki.android.core.ui.ChatScreen
import dev.loki.android.core.ui.ChatViewModel
import dev.loki.android.core.ui.PermissionItem
import dev.loki.android.core.ui.PermissionsScreen
import dev.loki.android.core.ui.SetupScreen
import dev.loki.android.core.ui.theme.LokiTheme
import dev.loki.android.core.ui.theme.ThemeMode
import dev.loki.android.core.ui.theme.ThemeRepository
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.ui.ModelLibraryScreen
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppScreen {
    SETUP,
    CHAT,
    PERMISSIONS,
    MODEL_LIBRARY
}

private data class PendingImport(val modelId: String, val fileName: String, val file: java.io.File)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var conversationManager: ConversationManager

    @Inject
    lateinit var sttEngine: SttEngine

    @Inject
    lateinit var themeRepository: ThemeRepository

    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var modelLibraryManager: ModelLibraryManager

    private lateinit var chatViewModel: ChatViewModel

    private var permissionRefreshTrigger by mutableStateOf(0)
    private var pendingImport by mutableStateOf<PendingImport?>(null)
    private var modelOperationProgress by mutableStateOf<Float?>(null)
    private var modelOperationError by mutableStateOf<String?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionRefreshTrigger++
    }

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch { importGgufModel(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        chatViewModel = ChatViewModel(
            conversationManager = conversationManager,
            sttEngine = sttEngine
        )

        setContent {
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val isFirstRunComplete by themeRepository.isFirstRunComplete.collectAsState(initial = true)
            val coroutineScope = rememberCoroutineScope()
            val modelManifest by modelLibraryManager.manifest.collectAsState()

            var currentScreen by remember { mutableStateOf<AppScreen?>(null) }

            LaunchedEffect(isFirstRunComplete) {
                if (currentScreen == null) {
                    currentScreen = if (!isFirstRunComplete) AppScreen.SETUP else AppScreen.CHAT
                }
            }

            val permissionsList = getTrackedPermissions(permissionRefreshTrigger)

            LokiTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        AppScreen.SETUP -> {
                            SetupScreen(
                                permissions = permissionsList,
                                onRequestAllPermissions = {
                                    requestRequiredPermissions()
                                },
                                onCompleteSetup = {
                                    coroutineScope.launch {
                                        themeRepository.setFirstRunComplete(true)
                                        currentScreen = AppScreen.CHAT
                                    }
                                },
                                onOpenModelLibrary = if (modelManifest.models.none {
                                        it.availability != ModelAvailability.NOT_DOWNLOADED
                                    }) {
                                    { currentScreen = AppScreen.MODEL_LIBRARY }
                                } else {
                                    null
                                }
                            )
                        }
                        AppScreen.PERMISSIONS -> {
                            PermissionsScreen(
                                permissions = permissionsList,
                                onRequestPermission = { perm ->
                                    requestPermissionLauncher.launch(arrayOf(perm))
                                },
                                onOpenSettings = {
                                    permissionManager.openAppSettings(this@MainActivity)
                                },
                                onNavigateBack = {
                                    currentScreen = AppScreen.CHAT
                                }
                            )
                        }
                        AppScreen.MODEL_LIBRARY -> {
                            ModelLibraryScreen(
                                models = modelManifest.models,
                                onNavigateBack = { currentScreen = AppScreen.CHAT },
                                onImport = {
                                    modelOperationError = null
                                    importModelLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                                },
                                onLoad = { modelId ->
                                    coroutineScope.launch {
                                        modelOperationError = null
                                        if (!modelLibraryManager.load(modelId)) {
                                            modelOperationError = "Unable to load the selected model."
                                        }
                                    }
                                },
                                onEject = {
                                    coroutineScope.launch {
                                        modelOperationError = null
                                        if (!modelLibraryManager.eject()) {
                                            modelOperationError = "No loaded model to eject."
                                        }
                                    }
                                },
                                onDelete = { modelId ->
                                    coroutineScope.launch {
                                        modelOperationError = null
                                        if (!modelLibraryManager.delete(modelId)) {
                                            modelOperationError = "Unable to delete the selected model."
                                        }
                                    }
                                },
                                pendingImportName = pendingImport?.fileName,
                                onConfirmImport = { name, family, runtime, format ->
                                    val pending = pendingImport ?: return@ModelLibraryScreen
                                    pendingImport = null
                                    coroutineScope.launch {
                                        finishImport(pending, name, family, runtime, format)
                                    }
                                },
                                onCancelImport = {
                                    pendingImport?.file?.parentFile?.deleteRecursively()
                                    pendingImport = null
                                },
                                operationProgress = modelOperationProgress,
                                errorMessage = modelOperationError
                            )
                        }
                        AppScreen.CHAT, null -> {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToPermissions = {
                                    currentScreen = AppScreen.PERMISSIONS
                                },
                                onNavigateToModelLibrary = {
                                    currentScreen = AppScreen.MODEL_LIBRARY
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun importGgufModel(uri: Uri) {
        modelOperationProgress = 0f
        modelOperationError = null
        val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "imported-model.gguf")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "imported-model.gguf" }
        val modelId = "import-${System.currentTimeMillis()}"
        val importer = ModelImporter(this, modelLibraryManager.managedStorage)
        val result = importer.copyFromUri(modelId, uri, name) { copied, total ->
            modelOperationProgress = total?.let { copied.toFloat() / it }
        }
        if (result !is dev.loki.android.core.llm.TransferResult.Completed) {
            modelOperationProgress = null
            modelOperationError = (result as? dev.loki.android.core.llm.TransferResult.Rejected)?.reason
                ?: "Unable to import the selected model."
            return
        }
        val target = importer.finalize(modelId, name)
        modelOperationProgress = null
        when (GgufModelDetector().detect(target)) {
            is ModelDetection.Detected -> {
                finishImport(
                    PendingImport(modelId, name, target),
                    name.substringBeforeLast('.'),
                    "",
                    ModelRuntime.LLAMA_CPP,
                    ModelFormat.GGUF,
                    result.sha256
                )
            }
            ModelDetection.Unknown -> pendingImport = PendingImport(modelId, name, target)
        }
    }

    private suspend fun finishImport(
        pending: PendingImport,
        displayName: String,
        family: String,
        runtime: ModelRuntime,
        format: ModelFormat,
        knownSha256: String? = null
    ) {
        val validator: ModelValidator = when (runtime) {
            ModelRuntime.LITERT_LM -> LiteRtModelValidator(this)
            ModelRuntime.LLAMA_CPP -> GgufModelValidator()
        }
        val validation = validator.validate(pending.file)
        if (validation !is ValidationResult.Valid) {
            pending.file.parentFile?.deleteRecursively()
            modelOperationError = (validation as ValidationResult.Invalid).reason
            return
        }
        modelLibraryManager.register(
            ModelRecord(
                id = pending.modelId,
                displayName = displayName,
                family = ModelMetadataField(family.ifBlank { null }),
                runtime = runtime,
                format = format,
                artifactPath = pending.file.relativeTo(modelLibraryManager.managedStorage.rootDirectory).path,
                artifactFileName = pending.file.name,
                sizeBytes = pending.file.length(),
                source = ModelSource.LOCAL_IMPORT,
                sha256 = knownSha256,
                availability = ModelAvailability.DOWNLOADED,
                importedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    override fun onResume() {
        super.onResume()
        permissionRefreshTrigger++
    }

    private fun getTrackedPermissions(@Suppress("UNUSED_PARAMETER") trigger: Int): List<PermissionItem> {
        val list = mutableListOf(
            PermissionItem(
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Microphone (STT)",
                description = "Required for offline voice speech recognition.",
                isRequired = true,
                state = permissionManager.checkPermission(this, Manifest.permission.RECORD_AUDIO)
            ),
            PermissionItem(
                permission = Manifest.permission.CALL_PHONE,
                title = "Phone Calls",
                description = "Enables placing calls directly via voice commands.",
                isRequired = false,
                state = permissionManager.checkPermission(this, Manifest.permission.CALL_PHONE)
            ),
            PermissionItem(
                permission = Manifest.permission.READ_CONTACTS,
                title = "Contacts",
                description = "Allows searching names and phone numbers in your address book.",
                isRequired = false,
                state = permissionManager.checkPermission(this, Manifest.permission.READ_CONTACTS)
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionItem(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Notifications",
                    description = "Displays assistant background execution alerts.",
                    isRequired = false,
                    state = permissionManager.checkPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        return list
    }

    private fun requestRequiredPermissions() {
        val toRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ungranted = toRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
