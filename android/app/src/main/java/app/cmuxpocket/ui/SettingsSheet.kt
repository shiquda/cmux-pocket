package app.cmuxpocket.ui

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
    isLandscape: Boolean,
    settingsManager: SettingsManager,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onConnectClick: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val theme = CmuxTheme.colors
    val uriHandler = LocalUriHandler.current

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
                                    settingsManager = settingsManager,

                                    onHostChange = onHostChange,
                                    onPortChange = onPortChange,
                                    onTokenChange = onTokenChange,
                                    onConnectClick = onConnectClick
                                )
                            }
                        }

                        // Right Column: Display, Fonts, Columns & Themes
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
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
                            SectionHeader(title = "CONNECTION & HOST", icon = Icons.Default.Cast, color = theme.primary)
                            Spacer(modifier = Modifier.height(6.dp))
                            ConnectionSection(
                                hostInput = hostInput,
                                portInput = portInput,
                                tokenInput = tokenInput,
                                connectionStatus = connectionStatus,
                                settingsManager = settingsManager,

                                onHostChange = onHostChange,
                                onPortChange = onPortChange,
                                onTokenChange = onTokenChange,
                                onConnectClick = onConnectClick
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
    settingsManager: SettingsManager,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnectClick: () -> Unit
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
                text = if (connectionStatus == ConnectionStatus.CONNECTED) "Connected" else connectionStatus.name.lowercase().replaceFirstChar { it.uppercase() },
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
                Text("Font Size", fontSize = 13.sp, color = theme.onSurface)
                Text("${fontSizeSp.toInt()} sp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.primary)
            }

            Slider(
                value = fontSizeSp,
                onValueChange = onFontSizeChange,
                valueRange = 10f..26f,
                steps = 15,
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
