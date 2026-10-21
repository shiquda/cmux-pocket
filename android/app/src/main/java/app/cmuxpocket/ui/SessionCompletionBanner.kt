package app.cmuxpocket.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cmuxpocket.R

/**
 * Capsule under the island pill announcing a session that finished while another one was on
 * screen. Tapping it opens the finished session; it fades out on its own otherwise.
 */
@Composable
fun SessionCompletionBannerHost(
    notice: SessionCompletionNotice?,
    extraCount: Int,
    onOpen: (SessionCompletionNotice) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = notice,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        label = "session_completion_banner",
        modifier = modifier
    ) { current ->
        if (current != null) {
            SessionCompletionBanner(
                notice = current,
                extraCount = extraCount,
                onClick = { onOpen(current) }
            )
        }
    }
}

@Composable
fun SessionCompletionBanner(
    notice: SessionCompletionNotice,
    extraCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val openLabel = stringResource(R.string.completion_banner_open)
    Row(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(theme.islandBg)
            .border(1.dp, theme.islandBorder, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = openLabel }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(theme.accentGreen)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = notice.workspaceName,
            color = theme.onIslandMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Text(
            text = " › ",
            color = theme.onIslandMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )

        notice.tabLabel?.let { tab ->
            Text(
                text = "$tab ",
                color = theme.onIsland,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        Text(
            text = notice.surfaceTitle,
            color = theme.onIsland,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(R.string.completion_banner_done),
            color = theme.accentGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )

        if (extraCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(theme.accentGreen.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = stringResource(R.string.completion_banner_more, extraCount),
                    color = theme.accentGreen,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "›",
            color = theme.onIslandMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
