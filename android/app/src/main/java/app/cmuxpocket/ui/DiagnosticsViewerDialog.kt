package app.cmuxpocket.ui

import app.cmuxpocket.diagnostics.DiagnosticsManager
import app.cmuxpocket.transport.ConnectionStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.R

@Composable
fun DiagnosticsViewerDialog(
    settingsManager: SettingsManager,
    connectionStatus: ConnectionStatus,
    onDismissRequest: () -> Unit
) {
    val theme = CmuxTheme.colors
    val context = LocalContext.current
    var logs by remember { mutableStateOf(DiagnosticsManager.getLogs()) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)),
            color = theme.containerBg,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.diag_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.onContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (DiagnosticsManager.isEnabled) theme.accentGreen.copy(alpha = 0.2f) else theme.controlBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (DiagnosticsManager.isEnabled) {
                                        stringResource(R.string.diag_status_enabled)
                                    } else {
                                        stringResource(R.string.diag_status_disabled)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (DiagnosticsManager.isEnabled) theme.accentGreen else theme.onContainerMuted
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.diag_subtitle),
                            fontSize = 11.5.sp,
                            color = theme.onContainerMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(theme.controlBg)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.diag_close_button),
                            tint = theme.onContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Console log box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.background)
                        .border(1.dp, theme.cardBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    if (logs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (!DiagnosticsManager.isEnabled) {
                                    stringResource(R.string.diag_empty_disabled)
                                } else {
                                    stringResource(R.string.diag_empty_no_logs)
                                },
                                fontSize = 12.sp,
                                color = theme.onContainerMuted,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { line ->
                                val color = when {
                                    line.contains("[AUTH]") -> theme.accentOrange
                                    line.contains("[RPC]") -> theme.primary
                                    line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> theme.accentRed
                                    line.contains("success", ignoreCase = true) || line.contains("CONNECTED") -> theme.accentGreen
                                    else -> theme.onSurfaceVariant
                                }
                                Text(
                                    text = line,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            DiagnosticsManager.clear()
                            logs = emptyList()
                            Toast.makeText(context, context.getString(R.string.diag_toast_cleared), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = theme.accentRed
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, theme.accentRed.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.diag_clear_button), fontSize = 13.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val bundle = DiagnosticsManager.exportDiagnosticsBundle(context, settingsManager, connectionStatus)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(context.getString(R.string.diag_clip_label), bundle)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.diag_toast_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = theme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.diag_export_button),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
