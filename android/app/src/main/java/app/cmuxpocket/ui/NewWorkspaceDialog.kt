package app.cmuxpocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NewWorkspaceDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (name: String, initialTerminal: Boolean) -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var createInitialTerminal by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF222228),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("New Workspace", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Workspace Name") },
                    placeholder = { Text("e.g. backend-api, my-feature") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF64B5F6),
                        unfocusedBorderColor = Color(0xFF555555)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = createInitialTerminal,
                        onCheckedChange = { createInitialTerminal = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00FF7F),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Create initial terminal tab", fontSize = 13.sp, color = Color(0xFFDDDDDD))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = nameInput.trim().ifEmpty { "New Workspace" }
                    onConfirm(finalName, createInitialTerminal)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White
                )
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFAAAAAA))
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun NewSurfaceDialog(
    workspaceName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (title: String?) -> Unit
) {
    var titleInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF222228),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("New Terminal Tab", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Add a new terminal session in '$workspaceName'",
                    fontSize = 13.sp,
                    color = Color(0xFFAAAAAA)
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Tab Title (Optional)") },
                    placeholder = { Text("e.g. zsh, logs, tests") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF7F),
                        unfocusedBorderColor = Color(0xFF555555)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalTitle = titleInput.trim().ifEmpty { null }
                    onConfirm(finalTitle)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C853),
                    contentColor = Color.White
                )
            ) {
                Text("Add Tab")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFAAAAAA))
            ) {
                Text("Cancel")
            }
        }
    )
}
