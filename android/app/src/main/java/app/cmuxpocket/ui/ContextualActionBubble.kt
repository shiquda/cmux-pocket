package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ContextualActionBubble(
    offset: IntOffset,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hasSelection: Boolean = false
) {
    val theme = CmuxTheme.colors
    Surface(
        modifier = modifier
            .offset { offset }
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, theme.controlBorder, RoundedCornerShape(14.dp)),
        color = theme.bubbleBg,
        tonalElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .width(190.dp)
                .padding(vertical = 4.dp)
        ) {
            BubbleItem(
                icon = Icons.Default.ContentCopy,
                iconTint = theme.primary,
                text = if (hasSelection) stringResource(R.string.bubble_copy_selection) else stringResource(R.string.bubble_copy_screen),
                onClick = {
                    onCopy()
                    onDismiss()
                }
            )

            BubbleItem(
                icon = Icons.Default.ContentPaste,
                iconTint = theme.accentGreen,
                text = stringResource(R.string.bubble_paste),
                onClick = {
                    onPaste()
                    onDismiss()
                }
            )

            BubbleItem(
                icon = Icons.Default.FlashOn,
                iconTint = theme.accentOrange,
                text = stringResource(R.string.bubble_interrupt),
                onClick = {
                    onInterrupt()
                    onDismiss()
                }
            )

            BubbleItem(
                icon = Icons.Default.CleaningServices,
                iconTint = theme.accentRed,
                text = stringResource(R.string.bubble_clear),
                onClick = {
                    onClear()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun BubbleItem(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = theme.onContainer
        )
    }
}
