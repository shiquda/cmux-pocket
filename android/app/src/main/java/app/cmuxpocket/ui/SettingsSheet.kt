package app.cmuxpocket.ui

import app.cmuxpocket.R
import app.cmuxpocket.BuildConfig
import app.cmuxpocket.protocol.GatewayCompatibility
import app.cmuxpocket.protocol.GatewayVersions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalContext
import app.cmuxpocket.diagnostics.DiagnosticsManager
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import android.app.Activity
import androidx.annotation.StringRes
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    onFloatingWindowSettingsChanged: () -> Unit = {},
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onConnectClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onClearScrollback: () -> Unit = {},
    onRecheckGatewayVersion: () -> Unit = {},
    isRefreshingGatewayVersion: Boolean = false,
    onDismissRequest: () -> Unit
) {
    val theme = CmuxTheme.colors
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentVersion = BuildConfig.VERSION_NAME
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showExportProfilesDialog by remember { mutableStateOf(false) }
    var isDiagnosticsEnabled by remember { mutableStateOf(settingsManager.isDiagnosticsEnabled) }
    var vibrateOnSessionComplete by remember { mutableStateOf(settingsManager.vibrateOnSessionComplete) }
    var floatingWindowEnabled by remember { mutableStateOf(settingsManager.floatingWindowEnabled) }
    var floatingWindowAutoEnter by remember { mutableStateOf(settingsManager.floatingWindowAutoEnter) }
    var pipAspectRatio by remember { mutableStateOf(settingsManager.pipAspectRatio) }
    var pipFontSizeSp by remember { mutableStateOf(settingsManager.pipFontSizeSp) }
    // Ordinal rather than the enum itself so the page survives the activity recreation that a
    // language switch triggers; [ROOT_MENU] means the root list is showing.
    var openSectionOrdinal by rememberSaveable { mutableStateOf(ROOT_MENU) }
    val openSection = SettingsSection.entries.getOrNull(openSectionOrdinal)
    var appLanguage by remember { mutableStateOf(settingsManager.appLanguage) }

    fun copyProfilesToClipboard(includeCredentials: Boolean) {
        val count = settingsManager.profiles.size
        val json = settingsManager.exportProfilesJson(includeCredentials)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cmux pocket host profiles", json))
        Toast.makeText(
            context,
            context.getString(
                if (includeCredentials) R.string.settings_exported_with_credentials_toast
                else R.string.settings_exported_toast,
                count
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    BackHandler(enabled = openSection != null) { openSectionOrdinal = ROOT_MENU }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (openSection == null) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        IconButton(
                            onClick = { openSectionOrdinal = ROOT_MENU },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                                tint = theme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = openSection?.let { stringResource(it.labelRes) }
                            ?: stringResource(R.string.settings_title),
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

            if (openSection == null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item { BrandHeroCard() }

                    item {
                        SettingsGroup(title = stringResource(R.string.settings_section_connection)) {
                            SettingsNavRow(
                                section = SettingsSection.CONNECTION,
                                summary = "$hostInput:$portInput",
                                onClick = { openSectionOrdinal = SettingsSection.CONNECTION.ordinal }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
                            SettingsNavRow(
                                section = SettingsSection.DISPLAY,
                                summary = stringResource(themeModeLabelRes(themeMode)),
                                onClick = { openSectionOrdinal = SettingsSection.DISPLAY.ordinal }
                            )
                            SettingsRowDivider()
                            SettingsNavRow(
                                section = SettingsSection.LANGUAGE,
                                summary = stringResource(appLanguage.labelRes),
                                onClick = { openSectionOrdinal = SettingsSection.LANGUAGE.ordinal }
                            )
                            SettingsRowDivider()
                            SettingsNavRow(
                                section = SettingsSection.FLOATING_WINDOW,
                                summary = stringResource(
                                    if (floatingWindowEnabled) R.string.settings_notif_on
                                    else R.string.settings_notif_off
                                ),
                                onClick = { openSectionOrdinal = SettingsSection.FLOATING_WINDOW.ordinal }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.settings_section_notifications)) {
                            SettingsNavRow(
                                section = SettingsSection.NOTIFICATIONS,
                                summary = stringResource(
                                    if (vibrateOnSessionComplete) R.string.settings_notif_on
                                    else R.string.settings_notif_off
                                ),
                                onClick = { openSectionOrdinal = SettingsSection.NOTIFICATIONS.ordinal }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.settings_group_support)) {
                            SettingsNavRow(
                                section = SettingsSection.UPDATES,
                                summary = "v$currentVersion",
                                onClick = { openSectionOrdinal = SettingsSection.UPDATES.ordinal }
                            )
                            SettingsRowDivider()
                            SettingsNavRow(
                                section = SettingsSection.OPENSOURCE,
                                onClick = { openSectionOrdinal = SettingsSection.OPENSOURCE.ordinal }
                            )
                            SettingsRowDivider()
                            SettingsNavRow(
                                section = SettingsSection.DIAGNOSTICS,
                                summary = stringResource(
                                    if (isDiagnosticsEnabled) R.string.settings_row_diagnostics_on
                                    else R.string.settings_row_diagnostics_off
                                ),
                                onClick = { openSectionOrdinal = SettingsSection.DIAGNOSTICS.ordinal }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.settings_group_data)) {
                            SettingsNavRow(
                                section = SettingsSection.STORAGE,
                                summary = stringResource(
                                    R.string.settings_row_profiles,
                                    settingsManager.profiles.size
                                ),
                                onClick = { openSectionOrdinal = SettingsSection.STORAGE.ordinal }
                            )
                        }
                    }

                    item {
                        SettingsListFooter(
                            currentVersion = currentVersion,
                            onOpenSourceClick = { uriHandler.openUri(SOURCE_URL) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (openSection) {
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
                                onRecheckGatewayVersion = onRecheckGatewayVersion,
                                isRefreshingGatewayVersion = isRefreshingGatewayVersion,
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

                        SettingsSection.FLOATING_WINDOW -> item {
                            FloatingWindowSection(
                                enabled = floatingWindowEnabled,
                                autoEnter = floatingWindowAutoEnter,
                                aspectRatio = pipAspectRatio,
                                fontSizeSp = pipFontSizeSp,
                                onEnabledChange = {
                                    floatingWindowEnabled = it
                                    settingsManager.floatingWindowEnabled = it
                                    onFloatingWindowSettingsChanged()
                                },
                                onAutoEnterChange = {
                                    floatingWindowAutoEnter = it
                                    settingsManager.floatingWindowAutoEnter = it
                                    onFloatingWindowSettingsChanged()
                                },
                                onAspectRatioChange = {
                                    pipAspectRatio = it
                                    settingsManager.pipAspectRatio = it
                                    onFloatingWindowSettingsChanged()
                                },
                                onFontSizeChange = {
                                    pipFontSizeSp = it
                                    settingsManager.pipFontSizeSp = it
                                }
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

                        SettingsSection.NOTIFICATIONS -> item {
                            NotificationsSection(
                                isVibrateEnabled = vibrateOnSessionComplete,
                                onToggleVibrate = {
                                    vibrateOnSessionComplete = it
                                    settingsManager.vibrateOnSessionComplete = it
                                }
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
                                    showExportProfilesDialog = true
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

    if (showExportProfilesDialog) {
        ExportProfilesDialog(
            profileCount = settingsManager.profiles.size,
            onDismiss = { showExportProfilesDialog = false },
            onExportWithoutCredentials = {
                showExportProfilesDialog = false
                copyProfilesToClipboard(includeCredentials = false)
            },
            onExportWithCredentials = {
                showExportProfilesDialog = false
                copyProfilesToClipboard(includeCredentials = true)
            }
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

/** Ordinal stored when the root menu is showing instead of a section page. */
private const val ROOT_MENU = -1

/** Sections reachable from the settings root menu. */
enum class SettingsSection(@StringRes val labelRes: Int, val icon: ImageVector) {
    CONNECTION(R.string.settings_section_connection, Icons.Default.Cast),
    DISPLAY(R.string.settings_section_display, Icons.Default.TextFields),
    LANGUAGE(R.string.settings_section_language, Icons.Default.Translate),
    FLOATING_WINDOW(R.string.settings_section_floating_window, Icons.Default.PictureInPictureAlt),
    UPDATES(R.string.settings_section_updates, Icons.Default.CloudDownload),
    OPENSOURCE(R.string.settings_section_opensource, Icons.Default.Code),
    DIAGNOSTICS(R.string.settings_section_diagnostics, Icons.Default.BugReport),
    STORAGE(R.string.settings_section_storage, Icons.Default.Save),
    NOTIFICATIONS(R.string.settings_section_notifications, Icons.Default.Notifications)
}

@StringRes
private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.LIGHT -> R.string.settings_theme_light
}

/** A titled group of rows on the settings root menu. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = CmuxTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = theme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = theme.cardBg)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        color = CmuxTheme.colors.cardBorder,
        modifier = Modifier.padding(start = 56.dp)
    )
}

/** One entry on the settings root menu: icon, name, current value and a chevron. */
@Composable
private fun SettingsNavRow(
    section: SettingsSection,
    onClick: () -> Unit,
    summary: String? = null
) {
    val theme = CmuxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconTile(icon = section.icon)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(section.labelRes),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = theme.onContainer
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    fontSize = 11.5.sp,
                    color = theme.onContainerMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
    }
}

/**
 * Scroll-away metadata at the end of the settings root list.
 *
 * Settings persist as they change, so there is no pinned action bar. The AGPL-3.0 source and
 * license notice still has to be reachable, which a list footer does without claiming screen
 * space from every section.
 */
@Composable
private fun SettingsListFooter(
    currentVersion: String,
    onOpenSourceClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_footer_version, currentVersion),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = theme.onSurfaceVariant
        )
        TextButton(
            onClick = onOpenSourceClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.heightIn(min = 40.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_footer_license),
                fontSize = 11.sp,
                color = theme.primary
            )
        }
        Text(
            text = stringResource(R.string.settings_footer_copyright),
            fontSize = 10.sp,
            color = theme.onSurfaceVariant
        )
    }
}

/**
 * Rounded tile behind a settings row glyph.
 *
 * [container]/[tint] default to the primary accent: DESIGN.md reserves the semantic accents for
 * state, so a tile only takes one of them when the row itself is stateful (destructive, warning).
 */
@Composable
fun SettingsIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = CmuxTheme.colors.primary.copy(alpha = 0.14f),
    tint: Color = CmuxTheme.colors.primary
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
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
                        text = stringResource(option.labelRes),
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSection(
    hostInput: String,
    portInput: String,
    tokenInput: String,
    connectionStatus: ConnectionStatus,
    connectionReadiness: ConnectionReadiness,
    settingsManager: SettingsManager,
    onFloatingWindowSettingsChanged: () -> Unit = {},
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onRecheckGatewayVersion: () -> Unit = {},
    isRefreshingGatewayVersion: Boolean = false,
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

            GatewayVersionCard(
                readiness = connectionReadiness,
                canRecheck = connectionStatus == ConnectionStatus.CONNECTED,
                isRefreshing = isRefreshingGatewayVersion,
                onRecheck = onRecheckGatewayVersion,
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
                    val swatchColor = Color(android.graphics.Color.parseColor(opt.hexColor))
                    val borderColor = if (isSelected) theme.primary else theme.controlBorder
                    // The swatch fill is chosen at runtime (user's terminal palette), so the label
                    // ink must follow that fill's luminance rather than the app theme.
                    val labelColor = if (swatchColor.luminance() > 0.45f) {
                        theme.inkOnLightFill
                    } else {
                        theme.inkOnDarkFill
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(swatchColor)
                            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
                            .clickable { onThemeChange(opt.hexColor) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(opt.nameRes),
                            fontSize = 11.sp,
                            color = labelColor,
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
                                color = theme.accentOrangeInk
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
                            tint = theme.onBrandGithub,
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

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 56.dp))

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
                    SettingsIconTile(icon = Icons.Default.BugReport)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_feedback), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_feedback_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 56.dp))

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
                    SettingsIconTile(icon = Icons.Default.Description)
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
                    SettingsIconTile(icon = Icons.Default.Terminal)
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

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 56.dp))

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
                    SettingsIconTile(icon = Icons.Default.Share)
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
fun NotificationsSection(
    isVibrateEnabled: Boolean,
    onToggleVibrate: (Boolean) -> Unit
) {
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                SettingsIconTile(icon = Icons.Default.Vibration)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.settings_notif_vibrate), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                    Text(stringResource(R.string.settings_notif_vibrate_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = isVibrateEnabled,
                onCheckedChange = onToggleVibrate,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.onPrimary,
                    checkedTrackColor = theme.accentGreen,
                    uncheckedThumbColor = theme.onSurfaceVariant,
                    uncheckedTrackColor = theme.surfaceVariant
                )
            )
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
                    SettingsIconTile(icon = Icons.Default.FileDownload)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_export_profiles), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_export_profiles_desc, profileCount), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 56.dp))

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
                    SettingsIconTile(icon = Icons.Default.FileUpload)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_import_profiles), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.onContainer)
                        Text(stringResource(R.string.settings_import_profiles_desc), fontSize = 11.5.sp, color = theme.onContainerMuted)
                    }
                }
                Text("›", fontSize = 16.sp, color = theme.onContainerMuted)
            }

            HorizontalDivider(color = theme.cardBorder, modifier = Modifier.padding(start = 56.dp))

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
                    SettingsIconTile(
                        icon = Icons.Default.CleaningServices,
                        container = theme.accentRed.copy(alpha = 0.16f),
                        tint = theme.accentRedInk
                    )
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


private const val MAC_GATEWAY_UPGRADE_COMMAND =
    "brew update && brew upgrade cmux-pocket && cmux-pocket setup"
private const val LATEST_RELEASE_URL = "https://github.com/shiquda/cmux-pocket/releases/latest"

/** Version transparency for the connected host plus the one-tap upgrade paths. */
@Composable
fun GatewayVersionCard(
    readiness: ConnectionReadiness,
    canRecheck: Boolean,
    isRefreshing: Boolean,
    onRecheck: () -> Unit,
) {
    val theme = CmuxTheme.colors
    val host = readiness.hostStatus
    val compatibility = readiness.compatibility
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showUpgradeGuide by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.conn_versions_title),
                    fontSize = 11.sp,
                    color = theme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                CompatibilityBadge(compatibility)
            }

            GatewayVersionRow(
                label = stringResource(R.string.conn_version_client),
                value = "v${BuildConfig.VERSION_NAME}",
            )
            GatewayVersionRow(
                label = stringResource(R.string.conn_version_gateway),
                value = host?.gatewayVersion?.let { "v$it" }
                    ?: stringResource(R.string.conn_version_unknown),
            )
            GatewayVersionRow(
                label = stringResource(R.string.conn_version_protocol),
                value = host?.protocolVersion?.let { "v$it" }
                    ?: stringResource(R.string.conn_version_unknown),
            )
            GatewayVersionRow(
                label = stringResource(R.string.conn_version_wake),
                value = when {
                    host == null -> stringResource(R.string.conn_version_unknown)
                    host.supportsWake -> stringResource(R.string.conn_version_wake_ready)
                    else -> stringResource(R.string.conn_version_wake_unavailable)
                },
                valueColor = when {
                    host == null -> theme.onSurfaceVariant
                    host.supportsWake -> theme.accentGreenInk
                    else -> theme.accentOrangeInk
                },
            )

            when (compatibility) {
                GatewayCompatibility.GATEWAY_OUTDATED -> Text(
                    stringResource(R.string.conn_version_gateway_outdated_body),
                    fontSize = 11.sp,
                    color = theme.accentOrangeInk,
                )
                GatewayCompatibility.INCOMPATIBLE -> Text(
                    stringResource(
                        R.string.conn_version_incompatible_body,
                        host?.protocolVersion ?: "—",
                        GatewayVersions.EXPECTED_PROTOCOL_MAJOR,
                    ),
                    fontSize = 11.sp,
                    color = theme.accentRedInk,
                )
                GatewayCompatibility.CLIENT_OUTDATED -> Text(
                    stringResource(R.string.conn_version_client_outdated_body),
                    fontSize = 11.sp,
                    color = theme.accentBlueInk,
                )
                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onRecheck,
                    enabled = canRecheck && !isRefreshing,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.conn_btn_recheck_version), fontSize = 12.sp)
                }
                if (compatibility == GatewayCompatibility.GATEWAY_OUTDATED ||
                    compatibility == GatewayCompatibility.INCOMPATIBLE
                ) {
                    TextButton(onClick = { showUpgradeGuide = true }) {
                        Text(stringResource(R.string.conn_btn_upgrade_guide), fontSize = 12.sp)
                    }
                }
                if (compatibility == GatewayCompatibility.CLIENT_OUTDATED) {
                    TextButton(onClick = { uriHandler.openUri(LATEST_RELEASE_URL) }) {
                        Text(stringResource(R.string.conn_btn_download_client), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showUpgradeGuide) {
        AlertDialog(
            onDismissRequest = { showUpgradeGuide = false },
            title = { Text(stringResource(R.string.conn_upgrade_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.conn_upgrade_body),
                        color = theme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Surface(
                        color = theme.cardBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, theme.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = MAC_GATEWAY_UPGRADE_COMMAND,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = theme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(MAC_GATEWAY_UPGRADE_COMMAND))
                        Toast.makeText(
                            context,
                            context.getString(R.string.conn_upgrade_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.conn_btn_copy_upgrade_cmd))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUpgradeGuide = false
                        onRecheck()
                    }
                ) {
                    Text(stringResource(R.string.conn_btn_recheck_version))
                }
            },
            containerColor = theme.containerBg,
            titleContentColor = theme.onContainer,
            textContentColor = theme.onContainer,
        )
    }
}

@Composable
private fun GatewayVersionRow(
    label: String,
    value: String,
    valueColor: Color = CmuxTheme.colors.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = CmuxTheme.colors.onSurfaceVariant)
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CompatibilityBadge(compatibility: GatewayCompatibility) {
    val theme = CmuxTheme.colors
    val (label, color, ink) = when (compatibility) {
        GatewayCompatibility.MATCHED ->
            Triple(stringResource(R.string.conn_version_match), theme.accentGreen, theme.accentGreenInk)
        GatewayCompatibility.GATEWAY_OUTDATED ->
            Triple(stringResource(R.string.conn_version_outdated_badge), theme.accentOrange, theme.accentOrangeInk)
        GatewayCompatibility.CLIENT_OUTDATED ->
            Triple(stringResource(R.string.conn_version_update_badge), theme.accentBlue, theme.accentBlueInk)
        GatewayCompatibility.INCOMPATIBLE ->
            Triple(stringResource(R.string.conn_version_incompatible_badge), theme.accentRed, theme.accentRedInk)
        GatewayCompatibility.UNKNOWN ->
            Triple(stringResource(R.string.conn_version_unknown), theme.onSurfaceVariant, theme.onSurfaceVariant)
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = ink,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}


@Composable
fun FloatingWindowSection(
    enabled: Boolean,
    autoEnter: Boolean,
    aspectRatio: Float,
    fontSizeSp: Float,
    onEnabledChange: (Boolean) -> Unit,
    onAutoEnterChange: (Boolean) -> Unit,
    onAspectRatioChange: (Float) -> Unit,
    onFontSizeChange: (Float) -> Unit
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnabledChange(!enabled) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_floating_window_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.onSurface
                    )
                    Text(
                        stringResource(R.string.settings_floating_window_desc),
                        fontSize = 10.sp,
                        color = theme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                HorizontalDivider(color = theme.divider)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAutoEnterChange(!autoEnter) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_floating_window_auto),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.onSurface
                        )
                        Text(
                            stringResource(
                                if (autoEnter) R.string.settings_floating_window_auto_desc_on
                                else R.string.settings_floating_window_auto_desc_off
                            ),
                            fontSize = 10.sp,
                            color = theme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoEnter, onCheckedChange = onAutoEnterChange)
                }

                HorizontalDivider(color = theme.divider)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_floating_window_aspect),
                        fontSize = 13.sp,
                        color = theme.onSurface
                    )
                    Text(
                        stringResource(R.string.settings_floating_window_aspect_value, aspectRatio),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.primary
                    )
                }

                Slider(
                    value = aspectRatio,
                    onValueChange = onAspectRatioChange,
                    valueRange = SettingsManager.MIN_PIP_ASPECT_RATIO..SettingsManager.MAX_PIP_ASPECT_RATIO,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.primary,
                        activeTrackColor = theme.primary,
                        inactiveTrackColor = theme.divider
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_floating_window_font),
                        fontSize = 13.sp,
                        color = theme.onSurface
                    )
                    Text(
                        stringResource(R.string.font_size_sp_value, fontSizeSp.toInt()),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.primary
                    )
                }

                Slider(
                    value = fontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = SettingsManager.MIN_PIP_FONT_SIZE..SettingsManager.MAX_PIP_FONT_SIZE,
                    steps = (SettingsManager.MAX_PIP_FONT_SIZE - SettingsManager.MIN_PIP_FONT_SIZE).toInt() - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.primary,
                        activeTrackColor = theme.primary,
                        inactiveTrackColor = theme.divider
                    )
                )

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PipWindowPreview(
                        aspectRatio = aspectRatio,
                        fontSizeSp = fontSizeSp,
                        modifier = Modifier.width(240.dp)
                    )
                }
            }
        }
    }
}
