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

object AgentCompletionNotifications {
    private const val channelId = "agent-completions"
    private const val channelName = "Agent completions"
    const val extraWorkspaceId = "app.cmuxpocket.extra.WORKSPACE_ID"
    const val extraSurfaceId = "app.cmuxpocket.extra.SURFACE_ID"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Completed agent turns from cmux"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, completion: AgentSessionCompleted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

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
        val agentLabel = completion.agentKind?.takeIf { it.isNotBlank() } ?: "Agent"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$agentLabel task complete")
            .setContentText("Tap to open the completed tab in cmux Pocket.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }
}
