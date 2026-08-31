package dev.loki.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.ModelImporter
import dev.loki.android.core.models.DownloadResult
import dev.loki.android.core.models.LiteRtModelDetector
import dev.loki.android.core.models.LiteRtModelValidator
import dev.loki.android.core.models.MetadataConfidence
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCatalog
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelDetection
import dev.loki.android.core.models.ModelDownloader
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelMetadataField
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRecordCapabilities
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.models.ValidationResult
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.ui.ChatScreen
import dev.loki.android.core.ui.ChatViewModel
import dev.loki.android.core.ui.ModelLibraryScreen
import dev.loki.android.core.ui.PermissionItem
import dev.loki.android.core.ui.PermissionsScreen
import dev.loki.android.core.ui.SetupScreen
import dev.loki.android.core.theme.LokiTheme
import dev.loki.android.core.theme.ThemeMode
import dev.loki.android.core.theme.ThemeRepository
import dev.loki.android.core.voice.stt.SttEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen {
    SETUP,
    CHAT,
    PERMISSIONS,
    MODEL_LIBRARY,
    AGENT_PLAYGROUND,
    SETTINGS
}

private data class PendingImport(val modelId: String, val fileName: String, val file: File)

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

    @Inject
    lateinit var bundledCatalog: ModelCatalog

    @Inject
    lateinit var agentConfigRepository: dev.loki.android.core.ui.AgentConfigRepository

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var agentPlaygroundViewModel: dev.loki.android.core.ui.AgentPlaygroundViewModel
    private lateinit var settingsViewModel: dev.loki.android.core.ui.SettingsViewModel

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
        lifecycleScope.launch { importModel(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val savedConfig = agentConfigRepository.getAgentConfig()
            conversationManager.setAgentConfig(savedConfig)
        }

        chatViewModel = ChatViewModel(
            conversationManager = conversationManager,
            sttEngine = sttEngine,
            modelLibraryManager = modelLibraryManager,
            bundledCatalog = bundledCatalog
        )

        agentPlaygroundViewModel = dev.loki.android.core.ui.AgentPlaygroundViewModel(
            context = this,
            conversationManager = conversationManager,
            agentConfigRepository = agentConfigRepository,
            modelLibraryManager = modelLibraryManager
        )

        settingsViewModel = dev.loki.android.core.ui.SettingsViewModel(
            themeRepository = themeRepository,
            conversationManager = conversationManager
        )

        setContent {
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val coroutineScope = rememberCoroutineScope()
            val modelManifest by modelLibraryManager.manifest.collectAsState()

            val backStack = remember { mutableStateListOf<AppScreen>() }

            fun navigateTo(screen: AppScreen) {
                if (backStack.lastOrNull() != screen) {
                    backStack.add(screen)
                }
            }

            fun goBack(): Boolean {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.size - 1)
                    return true
                }
                return false
            }

            // Navigation gate: runtime readiness drives routing, not isFirstRunComplete.
            // Auto-transition SETUP → CHAT once mandatory runtimes are LOADED.
            // When active LLM is direct-audio capable, ASR is optional.
            val activeLlmId = modelManifest.activeModels[ModelRuntime.LITERT_LM]
            val activeLlmRecord = modelManifest.models.firstOrNull { it.id == activeLlmId }
            val isAudioCapable = activeLlmRecord?.capabilities?.isAudioInputSupported == true
            val isLlmReady = modelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_LM)
            val bothReady = isLlmReady && (isAudioCapable || modelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_ASR))

            LaunchedEffect(modelManifest) {
                when {
                    backStack.isEmpty() -> {
                        // Cold start: route based on readiness
                        backStack.add(if (bothReady) AppScreen.CHAT else AppScreen.SETUP)
                    }
                    backStack.contains(AppScreen.SETUP) && bothReady -> {
                        // Both runtimes became ready while on Setup — auto-advance
                        backStack.clear()
                        backStack.add(AppScreen.CHAT)
                    }
                    backStack.lastOrNull() == AppScreen.CHAT && !bothReady -> {
                        // A required runtime was lost while in Chat (e.g., model deleted) — return to Setup
                        backStack.clear()
                        backStack.add(AppScreen.SETUP)
                    }
                }
            }

            val currentScreen = backStack.lastOrNull() ?: if (bothReady) AppScreen.CHAT else AppScreen.SETUP

            BackHandler(enabled = backStack.size > 1 && currentScreen != AppScreen.CHAT && currentScreen != AppScreen.SETUP) {
                if (!goBack()) {
                    finish()
                }
            }

            val permissionsList = getTrackedPermissions(permissionRefreshTrigger)

            LokiTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) +
                             androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(220)) { width -> width / 8 })
                                .togetherWith(
                                    androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(180)) +
                                    androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(180)) { width -> -width / 8 }
                                )
                        },
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            AppScreen.SETUP -> {
                                SetupScreen(
                                    permissions = permissionsList,
                                    models = modelManifest.models,
                                    catalog = bundledCatalog.models,
                                    llmReady = modelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_LM),
                                    asrReady = modelLibraryManager.isRuntimeReady(ModelRuntime.LITERT_ASR),
                                    onRequestAllPermissions = {
                                        requestRequiredPermissions()
                                    },
                                    onCompleteSetup = {
                                        coroutineScope.launch {
                                            themeRepository.setFirstRunComplete(true)
                                            backStack.clear()
                                            backStack.add(AppScreen.CHAT)
                                        }
                                    },
                                    onProvisionRuntime = {
                                        navigateTo(AppScreen.MODEL_LIBRARY)
                                    },
                                    onNavigateToAgentPlayground = {
                                        navigateTo(AppScreen.AGENT_PLAYGROUND)
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
                                        goBack()
                                    }
                                )
                            }
                            AppScreen.MODEL_LIBRARY -> {
                                ModelLibraryScreen(
                                    models = modelManifest.models,
                                    onNavigateBack = {
                                        if (!goBack()) {
                                            backStack.clear()
                                            backStack.add(if (bothReady) AppScreen.CHAT else AppScreen.SETUP)
                                        }
                                    },
                                    onImport = {
                                        modelOperationError = null
                                        importModelLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                                    },
                                    onLoad = { modelId ->
                                        coroutineScope.launch {
                                            modelOperationError = null
                                            modelOperationProgress = -1f
                                            if (!modelLibraryManager.load(modelId)) {
                                                 modelOperationError = "Unable to load the selected model."
                                            }
                                            modelOperationProgress = null
                                        }
                                    },
                                    onEject = { runtime ->
                                        coroutineScope.launch {
                                            modelOperationError = null
                                            if (!modelLibraryManager.eject(runtime)) {
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
                                    onConfirmImport = { name, family, runtime, format, supportsAudio ->
                                        val pending = pendingImport ?: return@ModelLibraryScreen
                                        pendingImport = null
                                        coroutineScope.launch {
                                            modelOperationProgress = -1f
                                            finishImport(pending, name, family, runtime, format, supportsAudio)
                                        }
                                    },
                                    onCancelImport = {
                                        pendingImport?.file?.parentFile?.deleteRecursively()
                                        pendingImport = null
                                    },
                                    catalog = bundledCatalog.models,
                                    onDownload = { entry ->
                                        coroutineScope.launch {
                                            downloadCatalogEntry(entry)
                                        }
                                    },
                                    operationProgress = modelOperationProgress,
                                    errorMessage = modelOperationError
                                )
                            }
                            AppScreen.AGENT_PLAYGROUND -> {
                                dev.loki.android.core.ui.AgentPlaygroundScreen(
                                    viewModel = agentPlaygroundViewModel,
                                    onNavigateBack = {
                                        goBack()
                                    },
                                    onNavigateToModelLibrary = {
                                        navigateTo(AppScreen.MODEL_LIBRARY)
                                    }
                                )
                            }
                            AppScreen.CHAT -> {
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    onNavigateToPermissions = {
                                        navigateTo(AppScreen.PERMISSIONS)
                                    },
                                    onNavigateToModelLibrary = {
                                        navigateTo(AppScreen.MODEL_LIBRARY)
                                    },
                                    onNavigateToAgentPlayground = {
                                        navigateTo(AppScreen.AGENT_PLAYGROUND)
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(AppScreen.SETTINGS)
                                    }
                                )
                            }
                            AppScreen.SETTINGS -> {
                                dev.loki.android.core.ui.SettingsScreen(
                                    viewModel = settingsViewModel,
                                    onNavigateBack = {
                                        goBack()
                                    },
                                    onNavigateToModelLibrary = {
                                        navigateTo(AppScreen.MODEL_LIBRARY)
                                    },
                                    onNavigateToAgentPlayground = {
                                        navigateTo(AppScreen.AGENT_PLAYGROUND)
                                    },
                                    onNavigateToPermissions = {
                                        navigateTo(AppScreen.PERMISSIONS)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun importModel(uri: Uri) {
        modelOperationProgress = 0f
        modelOperationError = null
        val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "imported-model")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "imported-model" }
        val modelId = "import-${System.currentTimeMillis()}"
        val importer = ModelImporter(this, modelLibraryManager.managedStorage)
        val result = importer.copyFromUri(modelId, uri, name) { copied, total ->
            modelOperationProgress = total?.let { copied.toFloat() / it }
        }
        if (result !is dev.loki.android.core.models.TransferResult.Completed) {
            modelOperationProgress = null
            modelOperationError = (result as? dev.loki.android.core.models.TransferResult.Rejected)?.reason
                ?: "Unable to import the selected model."
            return
        }
        val target = importer.finalize(modelId, name)

        val liteRtDetection = LiteRtModelDetector().detect(target)
        if (liteRtDetection is ModelDetection.Detected) {
            finishImport(
                pending = PendingImport(modelId, name, target),
                displayName = name.substringBeforeLast('.'),
                family = "",
                runtime = ModelRuntime.LITERT_LM,
                format = ModelFormat.LITERT_MODEL,
                supportsAudio = liteRtDetection.supportsAudio,
                knownSha256 = result.sha256
            )
            return
        }

        modelOperationProgress = null
        pendingImport = PendingImport(modelId, name, target)
    }

    private suspend fun finishImport(
        pending: PendingImport,
        displayName: String,
        family: String,
        runtime: ModelRuntime,
        format: ModelFormat,
        supportsAudio: Boolean = false,
        knownSha256: String? = null
    ) {
        try {
            val validator = LiteRtModelValidator()
            val validation = validator.validate(pending.file)
            if (validation !is ValidationResult.Valid) {
                pending.file.parentFile?.deleteRecursively()
                modelOperationError = (validation as ValidationResult.Invalid).reason
                return
            }

            val sha256 = knownSha256 ?: dev.loki.android.core.models.ModelTransfer.calculateSha256(pending.file)
            val containerInfo = dev.loki.android.core.models.LitertLmContainerInspector.inspect(pending.file)
            val isDirectAudio = if (containerInfo.isLitertLmContainer) {
                containerInfo.supportsAudioInput
            } else {
                supportsAudio
            }
            val confidence = if (containerInfo.isLitertLmContainer) {
                MetadataConfidence.VERIFIED
            } else if (supportsAudio) {
                MetadataConfidence.USER_CONFIRMED
            } else {
                MetadataConfidence.UNKNOWN
            }

            val artifact = ModelArtifact(
                fileName = pending.fileName,
                relativePath = pending.fileName,
                sizeBytes = pending.file.length(),
                sha256 = sha256,
                url = ""
            )

            val capabilities = ModelRecordCapabilities(
                audioInput = ModelMetadataField(
                    value = isDirectAudio,
                    confidence = confidence
                )
            )

            modelLibraryManager.register(
                ModelRecord(
                    id = pending.modelId,
                    displayName = displayName,
                    family = ModelMetadataField(family.ifBlank { null }),
                    runtime = runtime,
                    format = format,
                    artifacts = listOf(artifact),
                    source = ModelSource.LOCAL_IMPORT,
                    availability = ModelAvailability.DOWNLOADED,
                    importedAtEpochMs = System.currentTimeMillis(),
                    capabilities = capabilities
                )
            )
        } finally {
            modelOperationProgress = null
        }
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

    /**
     * Downloads all artifacts for a [ModelCatalogEntry] using [ModelDownloader] and registers
     * the resulting [ModelRecord] with [ModelLibraryManager].
     *
     * Progress is reflected through [modelOperationProgress] and errors through [modelOperationError].
     */
    private suspend fun downloadCatalogEntry(entry: ModelCatalogEntry) {
        // Skip if already registered
        if (modelLibraryManager.manifest.value.models.any { it.id == entry.id }) {
            modelOperationError = "${entry.displayName} is already in the model library."
            return
        }

        modelOperationError = null
        modelOperationProgress = 0f

        val downloader = ModelDownloader(modelLibraryManager.managedStorage)
        val downloadedArtifacts = mutableListOf<ModelArtifact>()
        val totalArtifacts = entry.artifacts.size

        try {
            entry.artifacts.forEachIndexed { index, artifact ->
                val connection = withContext(Dispatchers.IO) {
                    (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        requestMethod = "GET"
                        connect()
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    connection.inputStream.use { stream ->
                        downloader.downloadArtifact(
                            modelId = entry.id,
                            artifact = artifact,
                            input = stream,
                            onProgress = { bytesCopied, totalBytes ->
                                val artifactProgress = totalBytes?.let { bytesCopied.toFloat() / it } ?: -1f
                                // Weight progress across multiple artifacts
                                modelOperationProgress = (index + artifactProgress.coerceIn(0f, 1f)) / totalArtifacts
                            }
                        )
                    }
                }

                when (result) {
                    is DownloadResult.Completed -> {
                        downloadedArtifacts.add(
                            artifact.copy(sha256 = result.sha256)
                        )
                    }
                    is DownloadResult.Failed -> {
                        modelOperationError = "Download failed: ${result.reason}"
                        modelOperationProgress = null
                        return
                    }
                }
            }

            val hasAudioInput = entry.capabilities.any { it.equals("audio-input", ignoreCase = true) }
            val capabilities = ModelRecordCapabilities(
                audioInput = ModelMetadataField(
                    value = hasAudioInput,
                    confidence = MetadataConfidence.VERIFIED
                )
            )

            modelLibraryManager.register(
                ModelRecord(
                    id = entry.id,
                    displayName = entry.displayName,
                    family = ModelMetadataField(entry.family),
                    runtime = entry.runtime,
                    format = entry.format,
                    artifacts = downloadedArtifacts,
                    source = ModelSource.BUNDLED_CATALOG,
                    availability = ModelAvailability.DOWNLOADED,
                    importedAtEpochMs = System.currentTimeMillis(),
                    capabilities = capabilities
                )
            )
        } catch (e: Exception) {
            modelOperationError = "Download error: ${e.message ?: "Unknown error"}"
        } finally {
            modelOperationProgress = null
        }
    }
}
