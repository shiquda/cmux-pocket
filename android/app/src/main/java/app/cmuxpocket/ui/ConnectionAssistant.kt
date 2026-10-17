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
        Text("Connection check", color = theme.onSurface, style = MaterialTheme.typography.titleSmall)
        Text(readiness.summary, color = theme.onSurface, fontSize = 13.sp)
        if (readiness.started) {
            ConnectionStep.entries.filter { it != ConnectionStep.READY }.forEach { step ->
                val marker = when {
                    step.ordinal < readiness.step.ordinal -> "✓"
                    step == readiness.step && readiness.problem != null -> "!"
                    step == readiness.step -> "…"
                    else -> "○"
                }
                Text("$marker  ${step.label}", color = theme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (readiness.problem != null || readiness.ready) {
                Text(readiness.help, color = theme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Button(
            onClick = onCheckConnection,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Check connection") }
        Text(
            "Checks the host, port, and token above. This reconnects Pocket and loads your workspaces.",
            color = theme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showHostSetup = !showHostSetup }) { Text("Host setup") }
            if (tailscaleIntent != null) {
                TextButton(onClick = { context.startActivity(tailscaleIntent) }) { Text("Open Tailscale") }
            }
        }
        if (showHostSetup) {
            Text(
                "Install Tailscale on the Mac and phone, sign in to the same tailnet, and connect both. On the Mac, open cmux, then run:",
                color = theme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            SelectionContainer { Text("cmux-pocket setup --tailscale", color = theme.onSurface) }
            TextButton(onClick = { clipboard.setText(AnnotatedString("cmux-pocket setup --tailscale")) }) {
                Text("Copy setup command")
            }
            Text(
                "The Mac command starts or reuses the Gateway, verifies workspace access, and sets up private HTTPS forwarding. Enter the printed phone endpoint with port 443 and the existing Gateway token, then check the connection here. Saved profiles use encrypted storage.",
                color = theme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** Visible in both layouts even if authentication succeeds but bootstrap fails. */
@Composable
fun ConnectionReadinessNotice(readiness: ConnectionReadiness, onOpenSettings: () -> Unit) {
    if (readiness.problem == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(readiness.summary, fontSize = 13.sp)
            TextButton(onClick = onOpenSettings) { Text("Check connection in Settings") }
        }
    }
}
