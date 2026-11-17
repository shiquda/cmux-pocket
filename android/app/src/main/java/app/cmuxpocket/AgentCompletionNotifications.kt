package app.cmuxpocket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cmuxpocket.protocol.AgentSessionCompleted

/**
 * Outcome of one system-notification delivery attempt.
 *
 * The completion is already consumed by the time this is known, so the caller
 * surfaces a blocked outcome instead of silently dropping the alert.
 */
enum class NotificationDelivery {
    DELIVERED,
    PERMISSION_DENIED,
    CHANNEL_BLOCKED
}

object AgentCompletionNotifications {
    private const val channelId = "agent-completions"
    private const val groupPrefix = "agent-completions:"
    const val extraWorkspaceId = "app.cmuxpocket.extra.WORKSPACE_ID"
    const val extraSurfaceId = "app.cmuxpocket.extra.SURFACE_ID"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Returns why a completion notification cannot be posted, or null when it can.
     *
     * Covers both an app-level block and a channel the user silenced; the
     * Gateway cannot tell either apart from a delivered notification.
     */
    fun blockedReason(context: Context): NotificationDelivery? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationDelivery.PERMISSION_DENIED
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationDelivery.CHANNEL_BLOCKED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager?.getNotificationChannel(channelId)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return NotificationDelivery.CHANNEL_BLOCKED
            }
        }
        return null
    }

    fun show(
        context: Context,
        completion: AgentSessionCompleted,
        targetLabel: String? = null
    ): NotificationDelivery {
        createChannel(context)
        blockedReason(context)?.let { return it }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(extraWorkspaceId, completion.workspaceId)
            putExtra(extraSurfaceId, completion.surfaceId)
        }
        val requestCode = (completion.eventId ?: completion.surfaceId).hashCode() and Int.MAX_VALUE
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val agentLabel = completion.agentKind?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_agent_fallback)
        val completionLine = context.getString(R.string.notif_task_complete, agentLabel)
        val title = targetLabel ?: completionLine
        val text = if (targetLabel != null) completionLine else context.getString(R.string.notif_tap_to_open)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // One group per surface: Android otherwise bundles every completion (plus the keep-alive
            // notification) under an auto-summary whose tap cannot carry a surface target.
            .setGroup("$groupPrefix${completion.surfaceId}")
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
            NotificationDelivery.DELIVERED
        } catch (error: SecurityException) {
            // Permission can be revoked between the check and the post.
            NotificationDelivery.PERMISSION_DENIED
        }
    }
}
