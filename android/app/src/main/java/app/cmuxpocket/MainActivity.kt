package app.cmuxpocket

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.*
import android.content.res.Configuration
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ConnectionKeepAliveService::class.java)
        )
        AgentCompletionNotifications.createChannel(this)
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.agentSessionCompletions.collect { completion ->
                    AgentCompletionNotifications.show(this@MainActivity, completion)
                }
            }
        }
        handleNotificationIntent(intent)
        setContent {
            CmuxAppRoot(viewModel = viewModel)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val surfaceId = intent?.getStringExtra(AgentCompletionNotifications.extraSurfaceId) ?: return
        viewModel.navigateToSurface(
            intent.getStringExtra(AgentCompletionNotifications.extraWorkspaceId),
            surfaceId
        )
        intent.removeExtra(AgentCompletionNotifications.extraSurfaceId)
        intent.removeExtra(AgentCompletionNotifications.extraWorkspaceId)
    }
}

@Composable
fun CmuxAppRoot(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    var themeMode by remember { mutableStateOf(settingsManager.themeMode) }
    var selectedThemeHex by remember { mutableStateOf(settingsManager.terminalBgTheme) }
    var hostInput by remember { mutableStateOf(settingsManager.host) }
    var portInput by remember { mutableStateOf(settingsManager.port.toString()) }
    var tokenInput by remember { mutableStateOf(settingsManager.token) }
    var fontSizeSp by remember { mutableStateOf(settingsManager.fontSizeSp) }

    CmuxAppTheme(themeMode = themeMode) {
        CmuxApp(
            viewModel = viewModel,
            settingsManager = settingsManager,
            hostInput = hostInput,
            portInput = portInput,
            tokenInput = tokenInput,
            fontSizeSp = fontSizeSp,
            selectedThemeHex = selectedThemeHex,
            themeMode = themeMode,

            onHostChange = {
                hostInput = it
                settingsManager.host = it
            },
            onPortChange = {
                portInput = it
                settingsManager.port = it.toIntOrNull() ?: 8088
            },
            onTokenChange = {
                tokenInput = it
                settingsManager.token = it
            },
            onFontSizeChange = {
                fontSizeSp = it
                settingsManager.fontSizeSp = it
            },
            onThemeHexChange = {
                selectedThemeHex = it
                settingsManager.terminalBgTheme = it
            },
            onThemeModeChange = {
                themeMode = it
                settingsManager.themeMode = it
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmuxApp(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    hostInput: String,
    portInput: String,
    tokenInput: String,
    fontSizeSp: Float,
    selectedThemeHex: String,
    themeMode: ThemeMode,

    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeHexChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val theme = CmuxTheme.colors
    val activeScreenState by viewModel.activeScreenState.collectAsState()
    val status by viewModel.connectionStatus.collectAsState(initial = ConnectionStatus.DISCONNECTED)
    val statusMsg by viewModel.statusMessage.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val activeSurfaces by viewModel.activeSurfaces.collectAsState()
    val selectedSurfaceId by viewModel.selectedSurfaceId.collectAsState()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showNewWorkspaceDialog by remember { mutableStateOf(false) }
    var showNewSurfaceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showKeyboardPanel by remember { mutableStateOf(false) }
    var pendingCloseSurface by remember { mutableStateOf<SurfaceInfo?>(null) }

    fun requestCloseSurface(surfaceId: String) {
        val surface = activeSurfaces.firstOrNull { it.id == surfaceId } ?: return
        if (surface.requiresCloseConfirmation()) {
            pendingCloseSurface = surface
        } else {
            viewModel.closeSurface(surfaceId)
        }
    }
    var terminalInputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val context = LocalContext.current

    // Auto connect on launch with saved configuration
    LaunchedEffect(Unit) {
        viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
    }

    LaunchedEffect(status) {
        if (status == ConnectionStatus.ERROR) {
            Toast.makeText(context, "Connection Error: Check IP or start Gateway", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            // LANDSCAPE LAYOUT: Collapsible Left Sidebar + Full Height Terminal Stage
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.background)
            ) {
                LandscapeSidebar(
                    workspaces = workspaces,
                    activeWorkspace = activeWorkspace,
                    activeSurfaces = activeSurfaces,
                    selectedSurfaceId = selectedSurfaceId,
                    connectionStatus = status,
                    isExpanded = isSidebarExpanded,
                    onToggleExpand = { isSidebarExpanded = !isSidebarExpanded },
                    onSelectWorkspace = { key -> viewModel.selectWorkspace(key) },
                    onSelectSurface = { id -> viewModel.selectSurface(id) },
                    onCloseSurface = { id -> requestCloseSurface(id) },
                    onNewWorkspace = { showNewWorkspaceDialog = true },
                    onNewSurface = { showNewSurfaceDialog = true },
                    onSettingsClick = { showSettingsDialog = true }
                )

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TerminalStage(
                            surfaces = activeSurfaces,
                            activeScreenState = activeScreenState,
                            connectionStatus = status,
                            userFontSizeSp = fontSizeSp,
                            themeHex = selectedThemeHex,
                            onTapCanvas = {
                                terminalInputView?.requestKeyboard()
                            },
                            onTerminalScroll = viewModel::scrollTerminal,
                            onNewSurfaceClick = { showNewSurfaceDialog = true }
                        )
                    }

                    AccessoryKeyboardBar(
                        onSendKey = { key -> viewModel.sendInput(key) },
                        onOpenKeyboardPanel = { showKeyboardPanel = true }
                    )
                }
            }
        } else {
            // PORTRAIT LAYOUT: Standard TopBar + Horizontal Tab Strip + Bottom Accessory Bar
            Scaffold(
                topBar = {
                    Column {
                        WorkspaceTopBar(
                            workspace = activeWorkspace,
                            connectionStatus = status,
                            statusMessage = statusMsg,
                            onWorkspaceClick = { showWorkspaceSheet = true },
                            onNewSurfaceClick = { showNewSurfaceDialog = true },
                            onConnectSettingsClick = { showSettingsDialog = true },
                            onReconnectClick = {
                                viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
                            }
                        )

                        SurfaceTabBar(
                            surfaces = activeSurfaces,
                            selectedSurfaceId = selectedSurfaceId,
                            onSelectSurface = { surfaceId -> viewModel.selectSurface(surfaceId) },
                            onCloseSurface = { surfaceId -> requestCloseSurface(surfaceId) },
                            onNewSurface = { showNewSurfaceDialog = true }
                        )
                    }
                },
                bottomBar = {
                    AccessoryKeyboardBar(
                        onSendKey = { key -> viewModel.sendInput(key) },
                        onOpenKeyboardPanel = { showKeyboardPanel = true }
                    )
                },
                containerColor = theme.background
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    TerminalStage(
                        surfaces = activeSurfaces,
                        activeScreenState = activeScreenState,
                        connectionStatus = status,
                        userFontSizeSp = fontSizeSp,
                        themeHex = selectedThemeHex,
                        onTapCanvas = {
                            terminalInputView?.requestKeyboard()
                        },
                        onTerminalScroll = viewModel::scrollTerminal,
                        onNewSurfaceClick = { showNewSurfaceDialog = true }
                    )
                }
            }
        }

        // Single, stable root-level TerminalImeBridge instance
        TerminalImeBridge(
            onSendText = { text -> viewModel.sendInput(text) },
            onDelete = { viewModel.sendInput("\u007f") },
            onEnter = { viewModel.sendInput("\n") },
            onViewReady = { terminalInputView = it },
            modifier = Modifier.size(1.dp)
        )
    }

    // Shared Sheets & Dialogs
    if (showKeyboardPanel) {
        ComputerKeyboardPanel(
            onSendKey = { key -> viewModel.sendInput(key) },
            onDismissRequest = { showKeyboardPanel = false }
        )
    }

    if (showWorkspaceSheet) {
        WorkspaceBottomSheet(
            workspaces = workspaces,
            selectedWorkspaceKey = activeWorkspace?.stableKey,
            onSelectWorkspace = { key -> viewModel.selectWorkspace(key) },
            onNewWorkspaceClick = { showNewWorkspaceDialog = true },
            onDismissRequest = { showWorkspaceSheet = false }
        )
    }

    if (showNewWorkspaceDialog) {
        NewWorkspaceDialog(
            onDismissRequest = { showNewWorkspaceDialog = false },
            onConfirm = { name, initialTerminal ->
                showNewWorkspaceDialog = false
                viewModel.createWorkspace(name, initialTerminal)
            }
        )
    }

    if (showNewSurfaceDialog) {
        NewSurfaceDialog(
            workspaceName = activeWorkspace?.name ?: "Current Workspace",
            onDismissRequest = { showNewSurfaceDialog = false },
            onConfirm = { title ->
                showNewSurfaceDialog = false
                viewModel.createSurface(activeWorkspace?.stableKey, title)
            }
        )
    }

    pendingCloseSurface?.let { surface ->
        val running = surface.agentState == "working" || surface.agentState == "needs_input"
        AlertDialog(
            onDismissRequest = { pendingCloseSurface = null },
            title = { Text("Close tab?") },
            text = {
                Text(
                    if (running) {
                        "${surface.displayTitle} has a running process. Close it anyway?"
                    } else {
                        "${surface.displayTitle} is still running. Close it anyway?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = surface.id
                        pendingCloseSurface = null
                        viewModel.closeSurface(id)
                    }
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseSurface = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dedicated Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            hostInput = hostInput,
            portInput = portInput,
            tokenInput = tokenInput,
            fontSizeSp = fontSizeSp,
            selectedThemeHex = selectedThemeHex,
            themeMode = themeMode,
            connectionStatus = status,
            isLandscape = isLandscape,
            settingsManager = settingsManager,


            onHostChange = onHostChange,
            onPortChange = onPortChange,
            onTokenChange = onTokenChange,
            onFontSizeChange = onFontSizeChange,
            onThemeChange = onThemeHexChange,
            onThemeModeChange = onThemeModeChange,
            onConnectClick = {
                viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
            },
            onDismissRequest = { showSettingsDialog = false }
        )
    }
}
