package dev.loki.android.ui

import android.Manifest
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppScreen {
    SETUP,
    CHAT,
    PERMISSIONS
}

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

    private lateinit var chatViewModel: ChatViewModel

    private var permissionRefreshTrigger by mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionRefreshTrigger++
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
                        AppScreen.CHAT, null -> {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToPermissions = {
                                    currentScreen = AppScreen.PERMISSIONS
                                }
                            )
                        }
                    }
                }
            }
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
}
