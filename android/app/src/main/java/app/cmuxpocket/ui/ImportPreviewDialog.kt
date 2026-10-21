package app.cmuxpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.R

@Composable
fun ImportPreviewDialog(
    payload: CmuxImportPayload,
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
    onImportConfirmed: (profile: ConnectionProfile, connectImmediately: Boolean) -> Unit
) {
    val theme = CmuxTheme.colors
    var profileName by remember { mutableStateOf(payload.name) }
    var isTokenVisible by remember { mutableStateOf(false) }

    // Check for existing profile matching host and port
    val existingProfile = remember(payload) {
        settingsManager.profiles.firstOrNull {
            it.host.equals(payload.host, ignoreCase = true) && it.port == payload.port
        } ?: settingsManager.profiles.firstOrNull {
            it.name.equals(payload.name, ignoreCase = true)
        }
    }

    var updateExisting by remember { mutableStateOf(existingProfile != null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(18.dp)),
            color = theme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.dlg_import_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurface
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.dlg_import_cancel),
                            tint = theme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = theme.divider)

                // Duplicate profile warning banner
                if (existingProfile != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = theme.accentOrange.copy(alpha = if (theme.isDark) 0.18f else 0.12f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = theme.accentOrangeInk,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.dlg_import_existing_found),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accentOrangeInk
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.dlg_import_existing_desc,
                                    existingProfile.name,
                                    existingProfile.host,
                                    existingProfile.port
                                ),
                                fontSize = 12.sp,
                                color = theme.onContainerMuted
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = updateExisting,
                                    onClick = { updateExisting = true },
                                    label = { Text(stringResource(R.string.dlg_import_update_profile, existingProfile.name), fontSize = 11.sp) },
                                    leadingIcon = if (updateExisting) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                                FilterChip(
                                    selected = !updateExisting,
                                    onClick = { updateExisting = false },
                                    label = { Text(stringResource(R.string.dlg_import_save_as_new), fontSize = 11.sp) },
                                    leadingIcon = if (!updateExisting) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                // Profile Name field
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text(stringResource(R.string.dlg_import_profile_name), fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.onSurface,
                        unfocusedTextColor = theme.onSurface,
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Endpoint Summary Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = theme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dlg_import_connection_details),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurfaceVariant
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(stringResource(R.string.dlg_import_host_address), fontSize = 11.sp, color = theme.onSurfaceVariant)
                                Text(payload.host, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.onSurface)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.dlg_import_port), fontSize = 11.sp, color = theme.onSurfaceVariant)
                                Text(payload.port.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.onSurface)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(stringResource(R.string.dlg_import_protocol_security), fontSize = 11.sp, color = theme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (payload.tls) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = null,
                                        tint = if (payload.tls) theme.accentGreen else theme.accentOrange,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(if (payload.tls) R.string.dlg_import_protocol_wss else R.string.dlg_import_protocol_ws),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = theme.onSurface
                                    )
                                }
                            }

                            if (payload.isLoopback) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.dlg_import_usb_local), fontSize = 10.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = theme.divider.copy(alpha = 0.5f))

                        // Token display (masked by default)
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.dlg_import_gateway_auth_token), fontSize = 11.sp, color = theme.onSurfaceVariant)
                                TextButton(
                                    onClick = { isTokenVisible = !isTokenVisible },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(stringResource(if (isTokenVisible) R.string.dlg_import_token_hide else R.string.dlg_import_token_show), fontSize = 11.sp)
                                }
                            }
                            Text(
                                text = if (isTokenVisible) payload.token else payload.maskedToken(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.onSurface
                            )
                        }
                    }
                }

                // Security reminder
                Text(
                    text = stringResource(R.string.dlg_import_security_reminder),
                    fontSize = 11.sp,
                    color = theme.onSurfaceVariant
                )

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.dlg_import_cancel), fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val finalName = profileName.trim().ifBlank { payload.name }
                            val profileId = if (updateExisting && existingProfile != null) {
                                existingProfile.id
                            } else {
                                java.util.UUID.randomUUID().toString()
                            }
                            val profile = ConnectionProfile(
                                id = profileId,
                                name = finalName,
                                host = payload.host,
                                port = payload.port,
                                token = payload.token,
                                lastUsedAt = System.currentTimeMillis()
                            )
                            onImportConfirmed(profile, true)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = theme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dlg_import_confirm), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ImportErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    val theme = CmuxTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(stringResource(R.string.dlg_import_error_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(errorMessage, fontSize = 13.sp, color = theme.onSurfaceVariant)
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
            ) {
                Text(stringResource(R.string.dlg_import_error_ok), color = theme.onPrimary)
            }
        }
    )
}
