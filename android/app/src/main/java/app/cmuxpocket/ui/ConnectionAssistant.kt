package app.cmuxpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cmuxpocket.R
@Composable
fun ConnectionAssistant(
    readiness: ConnectionReadiness,
    onCheckConnection: () -> Unit,
) {
    val theme = CmuxTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showHostSetup by remember { mutableStateOf(false) }
    val tailscaleIntent = remember(context) {
        context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.conn_check_title), color = theme.onSurface, style = MaterialTheme.typography.titleSmall)
        val summaryText = when {
            readiness.problem != null -> readiness.problem
            readiness.ready -> stringResource(R.string.conn_summary_ready, readiness.workspaceCount, readiness.terminalCount)
            !readiness.started -> stringResource(R.string.conn_summary_untested)
            else -> "${stringResource(readiness.step.labelRes)}…"
        }
        Text(summaryText, color = theme.onSurface, fontSize = 13.sp)
        if (readiness.started) {
            ConnectionStep.entries.filter { it != ConnectionStep.READY }.forEach { step ->
                val marker = when {
                    step.ordinal < readiness.step.ordinal -> "✓"
                    step == readiness.step && readiness.problem != null -> "!"
                    step == readiness.step -> "…"
                    else -> "○"
                }
                Text(
                    text = stringResource(R.string.conn_step_item, marker, stringResource(step.labelRes)),
                    color = theme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            if (readiness.problem != null) {
                val helpRes = readiness.helpRes
                val helpText = if (helpRes != null) stringResource(helpRes) else readiness.help
                if (helpText.isNotEmpty()) {
                    Text(helpText, color = theme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        Button(
            onClick = onCheckConnection,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.conn_btn_check)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showHostSetup = !showHostSetup }) { Text(stringResource(R.string.conn_btn_host_setup)) }
            if (tailscaleIntent != null) {
                TextButton(onClick = { context.startActivity(tailscaleIntent) }) { Text(stringResource(R.string.conn_btn_open_tailscale)) }
            }
        }
        if (showHostSetup) {
            Text(
                stringResource(R.string.conn_host_setup_instructions),
                color = theme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Surface(
                color = theme.cardBg,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, theme.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "cmux-pocket setup --tailscale",
                            color = theme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString("cmux-pocket setup --tailscale")) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.conn_btn_copy_setup_cmd),
                            tint = theme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Visible in both layouts even if authentication succeeds but bootstrap fails. */
@Composable
fun ConnectionReadinessNotice(
    readiness: ConnectionReadiness,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (readiness.problem == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(readiness.summary, fontSize = 13.sp)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.conn_notice_check_in_settings)) }
        }
    }
}
