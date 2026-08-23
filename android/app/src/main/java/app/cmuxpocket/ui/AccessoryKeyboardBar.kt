package app.cmuxpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Persistent accessory key strip shown below the terminal stage.
 *
 * The leftmost keyboard icon opens the built-in computer keyboard panel;
 * tapping the terminal canvas remains the only way to launch the system IME.
 */
@Composable
fun AccessoryKeyboardBar(
    onSendKey: (String) -> Unit,
    onOpenKeyboardPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(theme.surfaceVariant)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Opens the built-in computer keyboard panel (does not toggle system IME)
        IconButton(
            onClick = onOpenKeyboardPanel,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = "Open Keyboard Panel",
                tint = theme.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        VerticalDivider(
            color = theme.divider,
            modifier = Modifier
                .height(24.dp)
                .padding(horizontal = 4.dp)
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(text = "ESC") { onSendKey("\u001b") }
            KeyButton(text = "TAB") { onSendKey("\t") }

            KeyButton(text = "^C") { onSendKey("\u0003") }
            KeyButton(text = "^D") { onSendKey("\u0004") }

            KeyButton(text = "↑") { onSendKey("\u001b[A") }
            KeyButton(text = "↓") { onSendKey("\u001b[B") }
            KeyButton(text = "←") { onSendKey("\u001b[D") }
            KeyButton(text = "→") { onSendKey("\u001b[C") }

            KeyButton(text = "Enter") { onSendKey("\r") }
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.accessoryKeyBg,
            contentColor = theme.accessoryKeyText
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
    ) {
        Text(text = text, fontSize = 12.sp)
    }
}
