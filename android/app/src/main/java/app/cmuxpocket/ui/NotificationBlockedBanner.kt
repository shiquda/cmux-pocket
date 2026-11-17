package app.cmuxpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cmuxpocket.NotificationDelivery
import app.cmuxpocket.R

/**
 * Tells the user that a completion could not reach the notification shade.
 *
 * Only shown after a real delivery attempt failed, so it never nags someone who
 * deliberately turned notifications off without a completion happening.
 */
@Composable
fun NotificationBlockedBanner(
    delivery: NotificationDelivery?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (delivery == null || delivery == NotificationDelivery.DELIVERED) return

    val theme = CmuxTheme.colors
    val message = stringResource(
        when (delivery) {
            NotificationDelivery.PERMISSION_DENIED -> R.string.notif_blocked_permission
            else -> R.string.notif_blocked_channel
        }
    )
    Row(
        modifier = modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.islandBg)
            .border(1.dp, theme.islandBorder, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(theme.accentOrange)
        )
        Text(
            text = message,
            color = theme.onIsland,
            fontSize = 11.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = stringResource(R.string.notif_blocked_open_settings),
            color = theme.accentBlue,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenSettings
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.notif_blocked_dismiss),
            tint = theme.onIslandMuted,
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(2.dp)
        )
    }
}
