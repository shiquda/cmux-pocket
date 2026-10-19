package app.cmuxpocket

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.*
import android.content.res.Configuration
import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Applies the in-app dictionary choice to every resource lookup in this activity. The stored
     * choice is read once in [CmuxPocketApp] so this never touches the keystore on the startup path.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage(AppLanguageStore.current))
    }


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
        handleDeepLinkIntent(intent)
        handleNotificationIntent(intent)
        setContent {
            CmuxAppRoot(viewModel = viewModel)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleDeepLinkIntent(intent)
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

    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.equals("cmux-pocket", ignoreCase = true) == true) {
            viewModel.setPendingImportUri(uri.toString())
            intent.data = null
        }
    }
}

@Composable
fun CmuxAppRoot(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    val settingsManager = remember { CmuxPocketApp.settingsManager }
    val pendingImportUri by viewModel.pendingImportUri.collectAsState()
    var importPayload by remember { mutableStateOf<CmuxImportPayload?>(null) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }

    LaunchedEffect(pendingImportUri) {
        val rawUri = pendingImportUri
        if (rawUri != null) {
            try {
                importPayload = CmuxImportUri.parse(rawUri)
                importErrorMessage = null
            } catch (e: Exception) {
                importPayload = null
                importErrorMessage = e.message ?: context.getString(R.string.main_invalid_config_link)
            }
        }
    }

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
            onScanQrClick = { showQrScanner = true },

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

        importPayload?.let { payload ->
            ImportPreviewDialog(
                payload = payload,
                settingsManager = settingsManager,
                onDismiss = {
                    importPayload = null
                    viewModel.clearPendingImportUri()
                },
                onImportConfirmed = { profile, connectImmediately ->
                    settingsManager.upsertProfile(profile)
                    settingsManager.activeProfileId = profile.id
                    hostInput = profile.host
                    portInput = profile.port.toString()
                    tokenInput = profile.token
                    settingsManager.host = profile.host
                    settingsManager.port = profile.port
                    settingsManager.token = profile.token
                    importPayload = null
                    viewModel.clearPendingImportUri()
                    if (connectImmediately) {
                        viewModel.connect(profile.host, profile.port, profile.token)
                    }
                }
            )
        }

        importErrorMessage?.let { errMsg ->
            ImportErrorDialog(
                errorMessage = errMsg,
                onDismiss = {
                    importErrorMessage = null
                    viewModel.clearPendingImportUri()
                }
            )
        }

        if (showQrScanner) {
            QrScannerDialog(
                onDismiss = { showQrScanner = false },
                onQrCodeDetected = { qrText ->
                    showQrScanner = false
                    viewModel.setPendingImportUri(qrText)
                }
            )
        }
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
    onScanQrClick: () -> Unit = {},
) {
    val theme = CmuxTheme.colors
    val activeScreenState by viewModel.activeScreenState.collectAsState()
    val status by viewModel.connectionStatus.collectAsState(initial = ConnectionStatus.DISCONNECTED)
    val statusMsg by viewModel.statusMessage.collectAsState()
    val connectionReadiness by viewModel.connectionReadiness.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val activeSurfaces by viewModel.activeSurfaces.collectAsState()
    val selectedSurfaceId by viewModel.selectedSurfaceId.collectAsState()

    val displaySurfaceId = selectedSurfaceId.orEmpty()
    val displayPort = portInput.toIntOrNull() ?: 8088
    val terminalDisplayState = remember(hostInput, displayPort, displaySurfaceId) {
        mutableStateOf(settingsManager.terminalDisplay(hostInput, displayPort, displaySurfaceId, fontSizeSp))
    }
    var terminalDisplay by terminalDisplayState
    // Persist the first view too, so changing another terminal's size does not change this one.
    LaunchedEffect(hostInput, displayPort, displaySurfaceId, terminalDisplay) {
        kotlinx.coroutines.delay(200)
        settingsManager.saveTerminalDisplay(hostInput, displayPort, displaySurfaceId, terminalDisplay)
    }
    DisposableEffect(hostInput, displayPort, displaySurfaceId) {
        onDispose {
            settingsManager.saveTerminalDisplay(hostInput, displayPort, displaySurfaceId, terminalDisplayState.value)
        }
    }
    val changeTerminalDisplay: (TerminalDisplayPreference) -> Unit = { terminalDisplay = it.normalized() }


    var isSidebarExpanded by remember { mutableStateOf(false) }
    var showSessionSurfaceDrawer by remember { mutableStateOf(false) }
    var showNewWorkspaceDialog by remember { mutableStateOf(false) }
    var showNewSurfaceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
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

    // Stable callbacks for the accessory bar: it sits inside the scope that recomposes on every
    // rendered terminal frame, so lambdas allocated per composition would drag all 14 keys
    // through recomposition on each frame.
    val accessorySendKey: (String) -> Unit = remember { { key -> viewModel.sendInput(key) } }
    val accessoryOpenKeyboardPanel: () -> Unit = remember { { showKeyboardPanel = true } }
    val accessoryOpenSettings: () -> Unit = remember { { showSettingsDialog = true } }

    // Auto connect on launch with saved configuration
    LaunchedEffect(Unit) {
        viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
    }

    LaunchedEffect(status) {
        if (status == ConnectionStatus.ERROR) {
            Toast.makeText(context, context.getString(R.string.main_connection_error), Toast.LENGTH_SHORT).show()
        }
    }

    // Orientation is derived from the real window size rather than LocalConfiguration: the
    // activity handles configuration changes itself, so a rotation must not depend on the
    // configuration CompositionLocal being refreshed.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
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
                    ConnectionReadinessNotice(
                        readiness = connectionReadiness,
                        onOpenSettings = { showSettingsDialog = true },
                        modifier = Modifier.statusBarsPadding()
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TerminalStage(
                            surfaces = activeSurfaces,
                            activeScreenState = activeScreenState,
                            connectionStatus = status,
                            displayPreference = terminalDisplay,
                            onDisplayPreferenceChange = changeTerminalDisplay,
                            themeHex = selectedThemeHex,
                            onTapCanvas = {
                                terminalInputView?.requestKeyboard()
                            },
                            onTerminalScroll = viewModel::scrollTerminal,
                            onNewSurfaceClick = { showNewSurfaceDialog = true },
                            onSendKey = { key -> viewModel.sendInput(key) },
                            onClearScreen = { viewModel.clearScrollback() }
                        )

                        // D2 breadcrumb pill: same entry point in landscape
                        FloatingIslandPill(
                            connectionStatus = status,
                            activeWorkspace = activeWorkspace,
                            activeSurface = activeSurfaces.firstOrNull { it.id == selectedSurfaceId } ?: activeSurfaces.firstOrNull(),
                            surfaceCount = activeSurfaces.size,
                            onClick = { showSessionSurfaceDrawer = true },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 8.dp)
                        )
                    }

                    AccessoryKeyboardBar(
                        onSendKey = accessorySendKey,
                        onOpenKeyboardPanel = accessoryOpenKeyboardPanel,
                        onSettingsClick = accessoryOpenSettings
                    )
                }
            }
        } else {
            // PORTRAIT LAYOUT: Proposal C (Terminal Pure · 极致沉浸悬浮岛)
            // 0dp fixed top bar consumption: terminal canvas 直接贴顶
            Scaffold(
                bottomBar = {
                        AccessoryKeyboardBar(
                            onSendKey = accessorySendKey,
                            onOpenKeyboardPanel = accessoryOpenKeyboardPanel,
                            onSettingsClick = accessoryOpenSettings
                        )
                },
                containerColor = theme.background
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Full Viewport Terminal Stage
                    TerminalStage(
                        surfaces = activeSurfaces,
                        activeScreenState = activeScreenState,
                        connectionStatus = status,
                        displayPreference = terminalDisplay,
                        onDisplayPreferenceChange = changeTerminalDisplay,
                        themeHex = selectedThemeHex,
                        onTapCanvas = {
                            terminalInputView?.requestKeyboard()
                        },
                        onTerminalScroll = viewModel::scrollTerminal,
                        onNewSurfaceClick = { showNewSurfaceDialog = true },
                        onSendKey = { key -> viewModel.sendInput(key) },
                        onClearScreen = { viewModel.clearScrollback() }
                    )

                    // Minimalist frosted Floating Island Pill overlaid at top center
                    FloatingIslandPill(
                        connectionStatus = status,
                        activeWorkspace = activeWorkspace,
                        activeSurface = activeSurfaces.firstOrNull { it.id == selectedSurfaceId } ?: activeSurfaces.firstOrNull(),
                        surfaceCount = activeSurfaces.size,
                        onClick = { showSessionSurfaceDrawer = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
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

        // Shared Sheets & Dialogs
        if (showKeyboardPanel) {
            ComputerKeyboardPanel(
                onSendKey = { key -> viewModel.sendInput(key) },
                onDismissRequest = { showKeyboardPanel = false },
                isLandscape = isLandscape
            )
        }

        if (showSessionSurfaceDrawer) {
            SessionSurfaceDrawer(
                workspaces = workspaces,
                activeWorkspace = activeWorkspace,
                surfaces = activeSurfaces,
                selectedSurfaceId = selectedSurfaceId,
                hostLabel = hostInput,
                onSelectSurface = { surfaceId -> viewModel.selectSurface(surfaceId) },
                onCloseSurface = { surfaceId -> requestCloseSurface(surfaceId) },
                onNewSurfaceClick = { showNewSurfaceDialog = true },
                onSelectWorkspace = { key -> viewModel.selectWorkspace(key) },
                onNewWorkspaceClick = { showNewWorkspaceDialog = true },
                onSettingsClick = { showSettingsDialog = true },
                onDismissRequest = { showSessionSurfaceDrawer = false }
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
                workspaceName = activeWorkspace?.name ?: stringResource(R.string.main_workspace_fallback),
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
                title = { Text(stringResource(R.string.main_close_tab_title)) },
                text = {
                    Text(
                        if (running) {
                            stringResource(R.string.main_close_tab_running, surface.displayTitle)
                        } else {
                            stringResource(R.string.main_close_tab_still_running, surface.displayTitle)
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
                        Text(stringResource(R.string.main_close))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCloseSurface = null }) {
                        Text(stringResource(R.string.main_cancel))
                    }
                }
            )
        }

        // Full-screen settings page (never a floating overlay over the terminal).
        if (showSettingsDialog) {
            BackHandler { showSettingsDialog = false }
            SettingsScreen(
                hostInput = hostInput,
                portInput = portInput,
                tokenInput = tokenInput,
                fontSizeSp = terminalDisplay.fontSizeSp,
                selectedThemeHex = selectedThemeHex,
                themeMode = themeMode,
                connectionStatus = status,
                connectionReadiness = connectionReadiness,
                settingsManager = settingsManager,


                onHostChange = onHostChange,
                onPortChange = onPortChange,
                onTokenChange = onTokenChange,
                onFontSizeChange = {
                    onFontSizeChange(it)
                    changeTerminalDisplay(terminalDisplay.copy(fontSizeSp = it, fitWidth = false, panX = 0f, panY = null))
                },
                onThemeChange = onThemeHexChange,
                onThemeModeChange = onThemeModeChange,
                onConnectClick = {
                    viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
                },
                onScanQrClick = onScanQrClick,
                onClearScrollback = { viewModel.clearScrollback() },
                onDismissRequest = { showSettingsDialog = false }
            )
        }
    }

}
