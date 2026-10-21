package app.cmuxpocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cmuxpocket.R

@Composable
fun RenameWorkspaceDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    val theme = CmuxTheme.colors
    var textValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialName,
                selection = TextRange(0, initialName.length)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = theme.containerBg,
        titleContentColor = theme.onContainer,
        textContentColor = theme.onContainer,
        title = { Text(stringResource(R.string.dlg_rename_workspace_title), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text(stringResource(R.string.dlg_rename_workspace_label)) },
                    placeholder = { Text(stringResource(R.string.dlg_rename_workspace_placeholder)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.onContainer,
                        unfocusedTextColor = theme.onContainer,
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = textValue.text.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
                enabled = textValue.text.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primary,
                    contentColor = theme.onPrimary
                )
            ) {
                Text(stringResource(R.string.dlg_rename_workspace_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = theme.onContainerMuted)
            ) {
                Text(stringResource(R.string.dlg_rename_cancel))
            }
        }
    )
}

@Composable
fun RenameSurfaceDialog(
    initialTitle: String,
    onDismissRequest: () -> Unit,
    onConfirm: (newTitle: String) -> Unit
) {
    val theme = CmuxTheme.colors
    var textValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialTitle,
                selection = TextRange(0, initialTitle.length)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = theme.containerBg,
        titleContentColor = theme.onContainer,
        textContentColor = theme.onContainer,
        title = { Text(stringResource(R.string.dlg_rename_surface_title), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text(stringResource(R.string.dlg_rename_surface_label)) },
                    placeholder = { Text(stringResource(R.string.dlg_rename_surface_placeholder)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.onContainer,
                        unfocusedTextColor = theme.onContainer,
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = textValue.text.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
                enabled = textValue.text.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primary,
                    contentColor = theme.onPrimary
                )
            ) {
                Text(stringResource(R.string.dlg_rename_surface_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = theme.onContainerMuted)
            ) {
                Text(stringResource(R.string.dlg_rename_cancel))
            }
        }
    )
}
