package app.cmuxpocket.ui

import app.cmuxpocket.R
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
import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch


data class ThemeOption(
    val id: String,
    @StringRes val nameRes: Int,
    val hexColor: String
)

val AVAILABLE_THEMES = listOf(
    ThemeOption("dark", R.string.settings_theme_option_dark, "#1E1E1E"),
    ThemeOption("oled", R.string.settings_theme_option_oled, "#000000"),
    ThemeOption("tokyo", R.string.settings_theme_option_tokyo, "#1A1B26"),
    ThemeOption("dracula", R.string.settings_theme_option_dracula, "#282A36")
)

private const val SOURCE_URL = "https://github.com/shiquda/cmux-pocket"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    hostInput: String,
    portInput: String,
    tokenInput: String,
    fontSizeSp: Float,
    selectedThemeHex: String,
    themeMode: ThemeMode,
    connectionStatus: ConnectionStatus,
    connectionReadiness: ConnectionReadiness,
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
    // Ordinal rather than the enum itself so the page survives the activity recreation that a
    // language switch triggers.
    var selectedSectionOrdinal by rememberSaveable { mutableStateOf(SettingsSection.GENERAL.ordinal) }
    val selectedSection = SettingsSection.entries[selectedSectionOrdinal]
    var appLanguage by remember { mutableStateOf(settingsManager.appLanguage) }

    Surface(modifier = Modifier.fillMaxSize(), color = theme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surfaceVariant)
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
                        text = stringResource(R.string.settings_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.onSurface
                    )
                }

                IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = theme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            HorizontalDivider(color = theme.divider)

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SettingsSectionRail(
                    selectedSection = selectedSection,
                    onSelect = { selectedSectionOrdinal = it.ordinal }
                )

                VerticalDivider(color = theme.divider)

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            SectionHeader(
                                title = stringResource(selectedSection.labelRes),
                                icon = selectedSection.icon,
                                color = theme.primary
                            )
                        }
                        when (selectedSection) {
                            SettingsSection.GENERAL -> item {
                                BrandHeroCard()
                            }

                            SettingsSection.CONNECTION -> item {
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

                            SettingsSection.DISPLAY -> item {
                                DisplaySection(
                                    fontSizeSp = fontSizeSp,
                                    selectedThemeHex = selectedThemeHex,
                                    themeMode = themeMode,
                                    onFontSizeChange = onFontSizeChange,
                                    onThemeChange = onThemeChange,
                                    onThemeModeChange = onThemeModeChange,
                                )
                            }

                            SettingsSection.LANGUAGE -> item {
                                LanguageSection(
                                    language = appLanguage,
                                    onLanguageChange = { language ->
                                        appLanguage = language
                                        settingsManager.appLanguage = language
                                        (context as? Activity)?.recreate()
                                    }
                                )
                            }

                            SettingsSection.UPDATES -> item {
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
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.settings_up_to_date, currentVersion),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                is UpdateCheckResult.Error -> {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.settings_update_failed, res.message),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            SettingsSection.OPENSOURCE -> item {
                                OpenSourceSection(
                                    onOpenRepo = { uriHandler.openUri(SOURCE_URL) },
                                    onOpenIssues = { uriHandler.openUri("$SOURCE_URL/issues") },
                                    onOpenLicenses = { showLicensesDialog = true }
                                )
                            }

                            SettingsSection.DIAGNOSTICS -> item {
                                DiagnosticsSection(
                                    isEnabled = isDiagnosticsEnabled,
                                    onToggleEnabled = {
                                        isDiagnosticsEnabled = it
                                        settingsManager.isDiagnosticsEnabled = it
                                    },
                                    onViewLogs = { showDiagnosticsDialog = true },
                                    onExportBundle = {
                                        val bundle = DiagnosticsManager.exportDiagnosticsBundle(
                                            context,
                                            settingsManager,
                                            connectionStatus
                                        )
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("cmux pocket diagnostics", bundle)
                                        )
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_diag_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }

                            SettingsSection.STORAGE -> item {
                                StorageSection(
                                    profileCount = settingsManager.profiles.size,
                                    onExportProfiles = {
                                        val json = settingsManager.exportProfilesJson()
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("cmux pocket host profiles", json)
                                        )
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.settings_exported_toast,
                                                settingsManager.profiles.size
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onImportProfiles = {
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip
                                        val text = if (clip != null && clip.itemCount > 0) {
                                            clip.getItemAt(0).text?.toString().orEmpty()
                                        } else {
                                            ""
                                        }
                                        val count = settingsManager.importProfilesJson(text)
                                        val message = if (count > 0) {
                                            context.getString(R.string.settings_imported_toast, count)
                                        } else {
                                            context.getString(R.string.settings_import_empty_toast)
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    },
                                    onClearScrollback = {
                                        onClearScrollback()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_scrollback_cleared_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }

                }
            }
            HorizontalDivider(color = theme.divider)

            // AGPL-required source/license notice and the explicit apply action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surfaceVariant)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { uriHandler.openUri(SOURCE_URL) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_footer_version, currentVersion),
                            fontSize = 11.sp,
                            color = theme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.settings_footer_license),
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
                        contentColor = theme.onPrimary
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
                    Text(
                        text = stringResource(R.string.settings_apply_save),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
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
                Text(
                    text = stringResource(R.string.settings_update_dialog_title, info.latestVersion),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.settings_update_dialog_body, info.currentVersion))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        uriHandler.openUri(info.downloadUrl)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text(stringResource(R.string.settings_update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text(stringResource(R.string.settings_update_later))
                }
            },
            containerColor = theme.containerBg,
            titleContentColor = theme.onContainer,
            textContentColor = theme.onContainer
        )
    }
}

/** Sections listed in the settings navigation rail. Labels stay short so they fit the rail width. */
enum class SettingsSection(@StringRes val labelRes: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_section_general, Icons.Default.Tune),
    CONNECTION(R.string.settings_section_connection, Icons.Default.Cast),
    DISPLAY(R.string.settings_section_display, Icons.Default.TextFields),
    LANGUAGE(R.string.settings_section_language, Icons.Default.Translate),
    UPDATES(R.string.settings_section_updates, Icons.Default.CloudDownload),
    OPENSOURCE(R.string.settings_section_opensource, Icons.Default.Code),
    DIAGNOSTICS(R.string.settings_section_diagnostics, Icons.Default.BugReport),
    STORAGE(R.string.settings_section_storage, Icons.Default.Save)
}

@Composable
private fun SettingsSectionRail(
    selectedSection: SettingsSection,
    onSelect: (SettingsSection) -> Unit
) {
    val theme = CmuxTheme.colors
    Column(
        modifier = Modifier
            .width(144.dp)
            .fillMaxHeight()
            .background(theme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        SettingsSection.entries.forEach { section ->
            val selected = section == selectedSection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (selected) theme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(section) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = section.icon,
                    contentDescription = null,
                    tint = if (selected) theme.primary else theme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(section.labelRes),
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) theme.primary else theme.onSurface,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun LanguageSection(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit
) {
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = theme.onContainerMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = theme.onContainer
                    )
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        fontSize = 11.5.sp,
                        color = theme.onContainerMuted
                    )
                }
            }

            AppLanguage.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguageChange(option) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when (option) {
                            AppLanguage.ENGLISH -> stringResource(R.string.settings_language_english)
                            AppLanguage.CHINESE -> stringResource(R.string.settings_language_chinese)
                        },
                        fontSize = 14.sp,
                        color = theme.onContainer
                    )
                    RadioButton(
                        selected = option == language,
                        onClick = { onLanguageChange(option) },
                        colors = RadioButtonDefaults.colors(selectedColor = theme.primary)
                    )
                }
            }
        }
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
    val context = LocalContext.current
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
            Text(stringResource(R.string.settings_profiles_title), fontSize = 11.sp, color = theme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
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
                                    contentDescription = stringResource(R.string.settings_profile_delete, profile.name),
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
                    label = { Text(stringResource(R.string.settings_add_host), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                AssistChip(
                    onClick = onScanQrClick,
                    label = { Text(stringResource(R.string.settings_scan_qr), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.settings_scan_qr_desc), modifier = Modifier.size(16.dp))
                    }
                )
            }

            OutlinedTextField(
                value = hostInput,
                onValueChange = onHostChange,
                enabled = activeProfileId != ConnectionProfile.USB_ID,
                label = { Text(stringResource(R.string.settings_host_or_url), fontSize = 12.sp) },
                placeholder = { Text(stringResource(R.string.settings_host_placeholder), fontSize = 12.sp, color = theme.onSurfaceVariant) },
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
                    label = { Text(stringResource(R.string.settings_port), fontSize = 12.sp) },
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
                    label = { Text(stringResource(R.string.settings_auth_token), fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = stringResource(R.string.settings_toggle_token),
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
                                context.getString(R.string.settings_scan_none_found)
                            } else {
                                val hostCountStr = if (found.size == 1) {
                                    context.getString(R.string.settings_scan_host_one)
                                } else {
                                    context.getString(R.string.settings_scan_host_many, found.size)
                                }
                                context.getString(R.string.settings_scan_found, hostCountStr)
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
                        Text(stringResource(R.string.settings_scanning), fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_scan_wifi), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    onClick = onConnectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.primary,
                        contentColor = theme.onPrimary
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
                    Text(stringResource(R.string.settings_reconnect), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                Text(stringResource(R.string.settings_latency, result.source, result.latencyMs), fontSize = 11.sp, color = theme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.settings_use), fontSize = 12.sp, color = theme.primary, fontWeight = FontWeight.Bold)
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
            title = { Text(stringResource(R.string.settings_add_host)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text(stringResource(R.string.settings_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it },
                        label = { Text(stringResource(R.string.settings_host_or_url)) },
                        placeholder = { Text(stringResource(R.string.settings_host_placeholder_short)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newPort,
                        onValueChange = { newPort = it },
                        label = { Text(stringResource(R.string.settings_port)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newToken,
                        onValueChange = { newToken = it },
                        label = { Text(stringResource(R.string.settings_auth_token)) },
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
                    Text(stringResource(R.string.settings_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
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
            Text(stringResource(R.string.settings_theme_mode_title), fontSize = 12.sp, color = theme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_theme_system), fontSize = 11.sp) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChange(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.settings_theme_dark), fontSize = 11.sp) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.settings_theme_light), fontSize = 11.sp) }
                )
            }

            // Font Size Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_font_size_title), fontSize = 13.sp, color = theme.onSurface)
                Text(stringResource(R.string.font_size_sp_value, fontSizeSp.toInt()), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.primary)
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
                    Text(stringResource(R.string.settings_font_builtin), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.onSurface)
                    Text(stringResource(R.string.settings_font_builtin_desc), fontSize = 10.sp, color = theme.onSurfaceVariant)
                }
            }

            // Terminal Background Theme Options
            Text(stringResource(R.string.settings_terminal_palette), fontSize = 12.sp, color = theme.onSurface, fontWeight = FontWeight.SemiBold)
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
                            text = stringResource(opt.nameRes),
                            fontSize = 11.sp,
                            color = theme.onPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BrandHeroCard() {
    val theme = CmuxTheme.colors
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
                .background(theme.primary.copy(alpha = 0.12f))
                .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = theme.primary,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = theme.onContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_brand_badge),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.primary
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_brand_tagline, "0.2.0"),
            fontSize = 12.sp,
            color = theme.onContainerMuted,
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
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
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
                            .background(theme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.settings_check_update),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.onContainer
                        )
                        Text(
                            text = stringResource(R.string.settings_current_version, currentVersion),
                            fontSize = 11.5.sp,
                            color = theme.onContainerMuted
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = theme.primary
                        )
                    } else if (updateResult is UpdateCheckResult.UpdateAvailable) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(theme.accentOrange.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_update_available_badge),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.accentOrange
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_check),
                            fontSize = 12.sp,
                            color = theme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "›", fontSize = 16.sp, color = theme.onContainerMuted)
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
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
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
                            .background(theme.brandGithub),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_repo), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_repo_value), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_star), fontSize = 12.sp, color = theme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
                }
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 48.dp))

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
                            .background(theme.accentOrange),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_feedback), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_feedback_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 48.dp))

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
                            .background(theme.accentGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_licenses), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_licenses_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
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
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
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
                            .background(theme.accentPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_diag_toggle), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(if (isEnabled) R.string.settings_diag_enabled_desc else R.string.settings_diag_disabled_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = theme.onPrimary,
                        checkedTrackColor = theme.accentGreen,
                        uncheckedThumbColor = theme.onSurfaceVariant,
                        uncheckedTrackColor = theme.surfaceVariant
                    )
                )
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 48.dp))

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
                            .background(theme.accentTeal),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_diag_export), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_diag_export_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_json_label), fontSize = 12.sp, color = theme.onContainerMuted)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
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
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
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
                            .background(theme.accentGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_export_profiles), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_export_profiles_desc, profileCount), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 48.dp))

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
                            .background(theme.accentYellow),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = theme.onAccentBright,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_import_profiles), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_import_profiles_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 48.dp))

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
                            .background(theme.accentRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = theme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_clear_scrollback), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_clear_scrollback_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }
        }
    }
}
