package app.cmuxpocket.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import app.cmuxpocket.diagnostics.DiagnosticsManager
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch


data class ThemeOption(
    val id: String,
    val name: String,
    val hexColor: String
)

val AVAILABLE_THEMES = listOf(
    ThemeOption("dark", "Default Dark", "#1E1E1E"),
    ThemeOption("oled", "OLED Black", "#000000"),
    ThemeOption("tokyo", "Tokyo Night", "#1A1B26"),
    ThemeOption("dracula", "Dracula Dark", "#282A36")
)

private const val SOURCE_URL = "https://github.com/shiquda/cmux-pocket"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    hostInput: String,
    portInput: String,
    tokenInput: String,
    fontSizeSp: Float,
    selectedThemeHex: String,
    themeMode: ThemeMode,
    connectionStatus: ConnectionStatus,
    connectionReadiness: ConnectionReadiness,
    isLandscape: Boolean,
    settingsManager: SettingsManager,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onConnectClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onClearScrollback: () -> Unit = {},
    onDismissRequest: () -> Unit
) {
    val theme = CmuxTheme.colors
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentVersion = "0.2.0"
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var isDiagnosticsEnabled by remember { mutableStateOf(settingsManager.isDiagnosticsEnabled) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWidth = if (isLandscape) 0.88f else 0.94f
        val dialogHeight = if (isLandscape) 0.92f else 0.88f

        Surface(
            modifier = Modifier
                .fillMaxWidth(dialogWidth)
                .fillMaxHeight(dialogHeight)
                .clip(RoundedCornerShape(18.dp)),
            color = theme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar with >=48dp close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Preferences & Connection",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = theme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                HorizontalDivider(color = theme.divider)

                // Body: 2-Column in Landscape, 1-Column in Portrait
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Left Column: Connection & Gateway
                        // Left Column: Connection & Gateway + Display
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                SectionHeader(title = "CONNECTION & HOST", icon = Icons.Default.Cast, color = theme.primary)
                            }
                            item {
                                ConnectionSection(
                                    hostInput = hostInput,
                                    portInput = portInput,
                                    tokenInput = tokenInput,
                                    connectionStatus = connectionStatus,
                                    connectionReadiness = connectionReadiness,
                                    settingsManager = settingsManager,
                                    onHostChange = onHostChange,
                                    onPortChange = onPortChange,
                                    onTokenChange = onTokenChange,
                                    onConnectClick = onConnectClick,
                                    onScanQrClick = onScanQrClick,
                                )
                            }
                            item {
                                HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            item {
                                SectionHeader(title = "DISPLAY & TERMINAL", icon = Icons.Default.TextFields, color = theme.primary)
                            }
                            item {
                                DisplaySection(
                                    fontSizeSp = fontSizeSp,
                                    selectedThemeHex = selectedThemeHex,
                                    themeMode = themeMode,
                                    onFontSizeChange = onFontSizeChange,
                                    onThemeChange = onThemeChange,
                                    onThemeModeChange = onThemeModeChange,
                                )
                            }
                        }

                        // Right Column: App Hub, Releases, Community, Diagnostics, Storage
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                BrandHeroCard()
                            }
                            item {
                                SectionHeader(title = "应用版本与更新 (RELEASE LIFECYCLE)", icon = Icons.Default.CloudDownload, color = theme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                ReleaseSection(
                                    currentVersion = currentVersion,
                                    isChecking = isCheckingUpdate,
                                    updateResult = updateResult,
                                    onCheckClick = {
                                        isCheckingUpdate = true
                                        scope.launch {
                                            val res = ReleaseUpdateChecker.check(currentVersion)
                                            isCheckingUpdate = false
                                            updateResult = res
                                            when (res) {
                                                is UpdateCheckResult.UpdateAvailable -> {
                                                    showUpdateDialog = true
                                                }
                                                is UpdateCheckResult.UpToDate -> {
                                                    Toast.makeText(context, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                                                }
                                                is UpdateCheckResult.Error -> {
                                                    Toast.makeText(context, "检查更新失败: ${res.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                            item {
                                SectionHeader(title = "开源社区与许可 (OPEN SOURCE & COMMUNITY)", icon = Icons.Default.Code, color = theme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OpenSourceSection(
                                    onOpenRepo = { uriHandler.openUri(SOURCE_URL) },
                                    onOpenIssues = { uriHandler.openUri("$SOURCE_URL/issues") },
                                    onOpenLicenses = { showLicensesDialog = true }
                                )
                            }
                            item {
                                SectionHeader(title = "开发者诊断与排查 (DIAGNOSTICS & LOGS)", icon = Icons.Default.BugReport, color = theme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                DiagnosticsSection(
                                    isEnabled = isDiagnosticsEnabled,
                                    onToggleEnabled = {
                                        isDiagnosticsEnabled = it
                                        settingsManager.isDiagnosticsEnabled = it
                                    },
                                    onViewLogs = { showDiagnosticsDialog = true },
                                    onExportBundle = {
                                        val bundle = DiagnosticsManager.exportDiagnosticsBundle(context, settingsManager, connectionStatus)
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("cmux pocket diagnostics", bundle))
                                        Toast.makeText(context, "已复制脱敏诊断包至剪贴板", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            item {
                                SectionHeader(title = "配置备份与存储 (PROFILES & STORAGE)", icon = Icons.Default.Save, color = theme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                StorageSection(
                                    profileCount = settingsManager.profiles.size,
                                    onExportProfiles = {
                                        val json = settingsManager.exportProfilesJson()
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("cmux pocket host profiles", json))
                                        Toast.makeText(context, "已导出 ${settingsManager.profiles.size} 个 Host Profiles 到剪贴板", Toast.LENGTH_SHORT).show()
                                    },
                                    onImportProfiles = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip
                                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                                        val count = settingsManager.importProfilesJson(text)
                                        if (count > 0) {
                                            Toast.makeText(context, "成功导入并合并 $count 个 Host Profiles", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "剪贴板中无有效的 Host Profiles JSON", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onClearScrollback = {
                                        onClearScrollback()
                                        Toast.makeText(context, "已清空终端回滚历史缓冲区并释放显存", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Portrait: Vertical Scrollable List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            BrandHeroCard()
                        }
                        item {
                            SectionHeader(title = "应用版本与更新 (RELEASE LIFECYCLE)", icon = Icons.Default.CloudDownload, color = theme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            ReleaseSection(
                                currentVersion = currentVersion,
                                isChecking = isCheckingUpdate,
                                updateResult = updateResult,
                                onCheckClick = {
                                    isCheckingUpdate = true
                                    scope.launch {
                                        val res = ReleaseUpdateChecker.check(currentVersion)
                                        isCheckingUpdate = false
                                        updateResult = res
                                        when (res) {
                                            is UpdateCheckResult.UpdateAvailable -> {
                                                showUpdateDialog = true
                                            }
                                            is UpdateCheckResult.UpToDate -> {
                                                Toast.makeText(context, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                                            }
                                            is UpdateCheckResult.Error -> {
                                                Toast.makeText(context, "检查更新失败: ${res.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            SectionHeader(title = "开源社区与许可 (OPEN SOURCE & COMMUNITY)", icon = Icons.Default.Code, color = theme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            OpenSourceSection(
                                onOpenRepo = { uriHandler.openUri(SOURCE_URL) },
                                onOpenIssues = { uriHandler.openUri("$SOURCE_URL/issues") },
                                onOpenLicenses = { showLicensesDialog = true }
                            )
                        }
                        item {
                            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            SectionHeader(title = "开发者诊断与排查 (DIAGNOSTICS & LOGS)", icon = Icons.Default.BugReport, color = theme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            DiagnosticsSection(
                                isEnabled = isDiagnosticsEnabled,
                                onToggleEnabled = {
                                    isDiagnosticsEnabled = it
                                    settingsManager.isDiagnosticsEnabled = it
                                },
                                onViewLogs = { showDiagnosticsDialog = true },
                                onExportBundle = {
                                    val bundle = DiagnosticsManager.exportDiagnosticsBundle(context, settingsManager, connectionStatus)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("cmux pocket diagnostics", bundle))
                                    Toast.makeText(context, "已复制脱敏诊断包至剪贴板", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            SectionHeader(title = "配置备份与存储 (PROFILES & STORAGE)", icon = Icons.Default.Save, color = theme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            StorageSection(
                                profileCount = settingsManager.profiles.size,
                                onExportProfiles = {
                                    val json = settingsManager.exportProfilesJson()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("cmux pocket host profiles", json))
                                    Toast.makeText(context, "已导出 ${settingsManager.profiles.size} 个 Host Profiles 到剪贴板", Toast.LENGTH_SHORT).show()
                                },
                                onImportProfiles = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                                    val count = settingsManager.importProfilesJson(text)
                                    if (count > 0) {
                                        Toast.makeText(context, "成功导入并合并 $count 个 Host Profiles", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "剪贴板中无有效的 Host Profiles JSON", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onClearScrollback = {
                                    onClearScrollback()
                                    Toast.makeText(context, "已清空终端回滚历史缓冲区并释放显存", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            SectionHeader(title = "CONNECTION & HOST", icon = Icons.Default.Cast, color = theme.primary)
                            Spacer(modifier = Modifier.height(6.dp))
                            ConnectionSection(
                                hostInput = hostInput,
                                portInput = portInput,
                                tokenInput = tokenInput,
                                connectionStatus = connectionStatus,
                                connectionReadiness = connectionReadiness,
                                settingsManager = settingsManager,
                                onHostChange = onHostChange,
                                onPortChange = onPortChange,
                                onTokenChange = onTokenChange,
                                onConnectClick = onConnectClick,
                                onScanQrClick = onScanQrClick,
                            )
                        }
                        item {
                            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item {
                            SectionHeader(title = "DISPLAY & TERMINAL", icon = Icons.Default.TextFields, color = theme.primary)
                            Spacer(modifier = Modifier.height(6.dp))
                            DisplaySection(
                                fontSizeSp = fontSizeSp,
                                selectedThemeHex = selectedThemeHex,
                                themeMode = themeMode,
                                onFontSizeChange = onFontSizeChange,
                                onThemeChange = onThemeChange,
                                onThemeModeChange = onThemeModeChange,
                            )
                        }
                    }
                }

                HorizontalDivider(color = theme.divider)

                // Footer actions and AGPL-required source/license notice.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { uriHandler.openUri(SOURCE_URL) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Column {
                            Text(
                                text = "cmux Pocket • v2.0",
                                fontSize = 11.sp,
                                color = theme.onSurfaceVariant
                            )
                            Text(
                                text = "AGPL-3.0 • No warranty • Source & license",
                                fontSize = 9.sp,
                                color = theme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onConnectClick()
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply & Save", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(
            onDismissRequest = { showLicensesDialog = false }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsViewerDialog(
            settingsManager = settingsManager,
            connectionStatus = connectionStatus,
            onDismissRequest = { showDiagnosticsDialog = false }
        )
    }

    if (showUpdateDialog && updateResult is UpdateCheckResult.UpdateAvailable) {
        val info = updateResult as UpdateCheckResult.UpdateAvailable
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Text("发现新版本 ${info.latestVersion}", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("当前版本 v${info.currentVersion}，官方最新发布已就绪。点击前往下载 APK。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        uriHandler.openUri(info.downloadUrl)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
                ) {
                    Text("前往下载 APK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("稍后")
                }
            },
            containerColor = Color(0xFF2C2C2E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE5E5EA)
        )
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSection(
    hostInput: String,
    portInput: String,
    tokenInput: String,
    connectionStatus: ConnectionStatus,
    connectionReadiness: ConnectionReadiness,
    settingsManager: SettingsManager,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
) {
    val theme = CmuxTheme.colors
    val scope = rememberCoroutineScope()
    var isTokenVisible by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(settingsManager.profiles) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<DiscoveredGateway>>(emptyList()) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val activeProfileId = settingsManager.activeProfileId

    fun applyHost(host: String, port: Int = portInput.toIntOrNull() ?: 8088, token: String = tokenInput) {
        onHostChange(host)
        onPortChange(port.toString())
        onTokenChange(token)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Profiles", fontSize = 11.sp, color = theme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (connectionStatus == ConnectionStatus.CONNECTED) connectionReadiness.summary else connectionStatus.name.lowercase().replaceFirstChar { it.uppercase() },
                fontSize = 11.sp,
                color = theme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                profiles.forEach { profile ->
                    InputChip(
                        selected = profile.id == activeProfileId ||
                            (profile.host == hostInput && profile.port.toString() == portInput),
                        onClick = {
                            profiles = settingsManager.applyProfile(profile)
                            applyHost(profile.host, profile.port, profile.token)
                        },
                        label = { Text(profile.name, fontSize = 11.sp) },
                        trailingIcon = if (profile.isBuiltIn) {
                            null
                        } else {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete ${profile.name}",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            profiles = settingsManager.deleteProfile(profile.id)
                                        }
                                )
                            }
                        }
                    )
                }
                AssistChip(
                    onClick = {
                        profileName = ""
                        showSaveDialog = true
                    },
                    label = { Text("Add Host", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                AssistChip(
                    onClick = onScanQrClick,
                    label = { Text("Scan QR", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code", modifier = Modifier.size(16.dp))
                    }
                )
            }

            OutlinedTextField(
                value = hostInput,
                onValueChange = onHostChange,
                enabled = activeProfileId != ConnectionProfile.USB_ID,
                label = { Text("Host or URL", fontSize = 12.sp) },
                placeholder = { Text("IP or https://tunnel.example.com", fontSize = 12.sp, color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = theme.onSurface,
                    unfocusedTextColor = theme.onSurface,
                    focusedBorderColor = theme.primary,
                    unfocusedBorderColor = theme.divider
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortChange,
                    enabled = activeProfileId != ConnectionProfile.USB_ID,
                    label = { Text("Port", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.onSurface,
                        unfocusedTextColor = theme.onSurface,
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.divider
                    ),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = onTokenChange,
                    label = { Text("Auth Token", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Token Visibility",
                                tint = theme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.onSurface,
                        unfocusedTextColor = theme.onSurface,
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.divider
                    ),
                    modifier = Modifier.weight(2f)
                )
            }

            ConnectionAssistant(
                readiness = connectionReadiness,
                onCheckConnection = onConnectClick,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        isScanning = true
                        scanMessage = null
                        scope.launch {
                            val extraPort = portInput.toIntOrNull()
                            val found = LanGatewayScanner.scan(extraPorts = listOfNotNull(extraPort))
                            scanResults = found
                            isScanning = false
                            scanMessage = if (found.isEmpty()) {
                                "No open gateway ports found on this subnet"
                            } else {
                                "Found ${found.size} host${if (found.size == 1) "" else "s"}"
                            }
                        }
                    },
                    enabled = !isScanning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning…", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Wi-Fi", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    onClick = onConnectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reconnect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            scanMessage?.let { message ->
                Text(message, fontSize = 11.sp, color = theme.onSurfaceVariant)
            }

            if (scanResults.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    scanResults.forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(theme.surface)
                                .clickable {
                                    applyHost(result.host, result.port, tokenInput)
                                    profiles = settingsManager.upsertProfile(
                                        ConnectionProfile(
                                            name = result.source,
                                            host = result.host,
                                            port = result.port,
                                            token = tokenInput,
                                            lastUsedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(result.endpointLabel(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.onSurface)
                                Text("${result.source} • ${result.latencyMs} ms", fontSize = 11.sp, color = theme.onSurfaceVariant)
                            }
                            Text("Use", fontSize = 12.sp, color = theme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        var newHost by remember { mutableStateOf("") }
        var newPort by remember { mutableStateOf(portInput.ifBlank { "8088" }) }
        var newToken by remember { mutableStateOf(tokenInput) }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Add Host") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it },
                        label = { Text("Host or URL") },
                        placeholder = { Text("host.example or https://tunnel.example") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newPort,
                        onValueChange = { newPort = it },
                        label = { Text("Port") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newToken,
                        onValueChange = { newToken = it },
                        label = { Text("Auth Token") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val host = newHost.trim()
                        if (host.isBlank()) return@TextButton
                        val name = profileName.trim().ifBlank { host }
                        val saved = settingsManager.upsertProfile(
                            ConnectionProfile(
                                name = name,
                                host = host,
                                port = newPort.toIntOrNull() ?: 8088,
                                token = newToken,
                                lastUsedAt = System.currentTimeMillis()
                            )
                        )
                        profiles = saved
                        applyHost(host, newPort.toIntOrNull() ?: 8088, newToken)
                        showSaveDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySection(
    fontSizeSp: Float,
    selectedThemeHex: String,
    themeMode: ThemeMode,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val theme = CmuxTheme.colors

    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Theme Mode Selection (System / Dark / Light)
            Text("App Theme Mode", fontSize = 12.sp, color = theme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                    label = { Text("System", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChange(ThemeMode.DARK) },
                    label = { Text("Dark", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                    label = { Text("Light", fontSize = 11.sp) }
                )
            }

            // Font Size Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Font Size · This Terminal", fontSize = 13.sp, color = theme.onSurface)
                Text("${fontSizeSp.toInt()} sp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.primary)
            }

            Slider(
                value = fontSizeSp,
                onValueChange = onFontSizeChange,
                valueRange = 2f..48f,
                steps = 45,
                colors = SliderDefaults.colors(
                    thumbColor = theme.primary,
                    activeTrackColor = theme.primary,
                    inactiveTrackColor = theme.divider
                )
            )

            // Built-in Font Notice
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.surface)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FontDownload,
                    contentDescription = null,
                    tint = theme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Maple Mono NF (Built-in)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.onSurface)
                    Text("Nerd Font v3 Icons + CJK Monospace", fontSize = 10.sp, color = theme.onSurfaceVariant)
                }
            }

            // Terminal Background Theme Options
            Text("Terminal Canvas Palette", fontSize = 12.sp, color = theme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AVAILABLE_THEMES.forEach { opt ->
                    val isSelected = opt.hexColor == selectedThemeHex
                    val borderColor = if (isSelected) theme.primary else Color.Transparent

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(android.graphics.Color.parseColor(opt.hexColor)))
                            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                            .clickable { onThemeChange(opt.hexColor) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = opt.name.take(6),
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BrandHeroCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141E2E))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = Color(0xFF0A84FF),
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "cmux pocket",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x260A84FF))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Open Source",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0A84FF)
                )
            }
        }

        Text(
            text = "cmux companion • multi-surface terminal client • v0.2.0",
            fontSize = 12.sp,
            color = Color(0xFF8E8E93),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ReleaseSection(
    currentVersion: String,
    isChecking: Boolean,
    updateResult: UpdateCheckResult?,
    onCheckClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isChecking, onClick = onCheckClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF0A84FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "检查官网更新",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "当前版本 v$currentVersion",
                            fontSize = 11.5.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF0A84FF)
                        )
                    } else if (updateResult is UpdateCheckResult.UpdateAvailable) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x33FF9F0A))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "有新版",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9F0A)
                            )
                        }
                    } else {
                        Text(
                            text = "检查",
                            fontSize = 12.sp,
                            color = Color(0xFF0A84FF),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "›", fontSize = 16.sp, color = Color(0xFF636366))
                }
            }
        }
    }
}

@Composable
fun OpenSourceSection(
    onOpenRepo: () -> Unit,
    onOpenIssues: () -> Unit,
    onOpenLicenses: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRepo)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF24292E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("GitHub 源码仓库", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("shiquda/cmux-pocket", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★ Star", fontSize = 12.sp, color = Color(0xFF0A84FF), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = Color(0xFF636366))
                }
            }

            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(start = 48.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenIssues)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFFFF9F0A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("问题反馈与功能提议", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("提交 GitHub Issue 报告", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Text("›", fontSize = 16.sp, color = Color(0xFF636366))
            }

            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(start = 48.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF30D158)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("开源协议与依赖声明", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("GNU AGPL-3.0 及三方库许可", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Text("›", fontSize = 16.sp, color = Color(0xFF636366))
            }
        }
    }
}

@Composable
fun DiagnosticsSection(
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onViewLogs: () -> Unit,
    onExportBundle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onViewLogs)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFFBF5AF2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("实时网关连接诊断日志", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text(if (isEnabled) "查看 WebSocket & RPC 诊断帧" else "未开启日志记录 (点击查看)", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF30D158),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color(0xFF39393D)
                    )
                )
            }

            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(start = 48.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExportBundle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF64D2FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("一键导出故障诊断包", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("脱敏配置与近 500 行网络诊断 (JSON)", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("JSON", fontSize = 12.sp, color = Color(0xFF8E8E93))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = Color(0xFF636366))
                }
            }
        }
    }
}

@Composable
fun StorageSection(
    profileCount: Int,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onClearScrollback: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExportProfiles)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF32D74B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("导出 Host 配置与预设", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("备份全部 $profileCount 个 Profiles (JSON 复制到剪贴板)", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Text("›", fontSize = 16.sp, color = Color(0xFF636366))
            }

            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(start = 48.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onImportProfiles)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFFFFD60A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("导入配置 (从剪贴板)", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("从剪贴板读取 JSON 并合并入现有配置", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Text("›", fontSize = 16.sp, color = Color(0xFF636366))
            }

            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(start = 48.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClearScrollback)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFFFF453A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("清空终端历史回滚缓冲区", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("重置 Scrollback 缓冲区并释放显存", fontSize = 11.5.sp, color = Color(0xFF8E8E93))
                    }
                }
                Text("›", fontSize = 16.sp, color = Color(0xFF636366))
            }
        }
    }
}
