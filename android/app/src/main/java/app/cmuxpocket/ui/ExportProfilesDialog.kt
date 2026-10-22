package app.cmuxpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.cmuxpocket.R

/**
 * Asks whether the clipboard backup should carry Gateway access tokens before anything is
 * written. Dismissing the dialog never touches the clipboard.
 */
@Composable
fun ExportProfilesDialog(
    profileCount: Int,
    onDismiss: () -> Unit,
    onExportWithoutCredentials: () -> Unit,
    onExportWithCredentials: () -> Unit
) {
    val theme = CmuxTheme.colors
    // `usePlatformDefaultWidth = false` only relaxes the window width to match_parent; the height
    // keeps wrapping the content without a budget. A column of fixed-height children then measures
    // taller than a short window (landscape, split screen, large system font) and the window clips
    // the overflow, leaving the credential buttons unreachable. Capping the surface and scrolling
    // the column keeps every action reachable; the reserve covers the status and navigation bars
    // plus a small margin, which the dialog window itself does not subtract.
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 96.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(18.dp)),
            color = theme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_export_dialog_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurface
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.settings_export_dialog_cancel),
                            tint = theme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = theme.divider)

                Text(
                    text = stringResource(R.string.settings_export_dialog_body, profileCount),
                    fontSize = 13.sp,
                    color = theme.onSurfaceVariant
                )

                Button(
                    onClick = onExportWithoutCredentials,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.settings_export_without_credentials),
                        color = theme.onPrimary
                    )
                }

                OutlinedButton(
                    onClick = onExportWithCredentials,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_export_with_credentials))
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_export_dialog_cancel))
                }
            }
        }
    }
}
