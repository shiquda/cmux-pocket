package app.cmuxpocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
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
        Text(readiness.summary, color = theme.onSurface, fontSize = 13.sp)
        if (readiness.started) {
            ConnectionStep.entries.filter { it != ConnectionStep.READY }.forEach { step ->
                val marker = when {
                    step.ordinal < readiness.step.ordinal -> "✓"
                    step == readiness.step && readiness.problem != null -> "!"
                    step == readiness.step -> "…"
                    else -> "○"
                }
                Text(stringResource(R.string.conn_step_item, marker, step.label), color = theme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (readiness.problem != null || readiness.ready) {
                Text(readiness.help, color = theme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Button(
            onClick = onCheckConnection,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.conn_btn_check)) }
        Text(
            stringResource(R.string.conn_check_desc),
            color = theme.onSurfaceVariant,
            fontSize = 11.sp,
        )
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
            SelectionContainer { Text("cmux-pocket setup --tailscale", color = theme.onSurface) }
            TextButton(onClick = { clipboard.setText(AnnotatedString("cmux-pocket setup --tailscale")) }) {
                Text(stringResource(R.string.conn_btn_copy_setup_cmd))
            }
            Text(
                stringResource(R.string.conn_host_setup_details),
                color = theme.onSurfaceVariant,
                fontSize = 12.sp,
            )
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
