package com.company.supportvisit.notifications

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
import com.company.supportvisit.R
import com.company.supportvisit.TasksActivity
import com.company.supportvisit.VisitDetailsActivity
import com.company.supportvisit.data.TaskEntity
import com.company.supportvisit.data.VisitEntity

/**
 * The app's few notifications: a visit report that couldn't be sent (after all automatic
 * retries), and a task a teammate assigned to you. Each has its own channel, so people can
 * turn either one off in the phone's settings.
 */
object Notifier {

    private const val CHANNEL_SENDING = "sending"
    private const val CHANNEL_TASKS = "tasks"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SENDING, context.getString(R.string.channel_sending), NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Android 13+ asks the user once; before that, notifications are allowed by default. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun visitFailed(context: Context, visit: VisitEntity) {
        val open = VisitDetailsActivity.intent(context, visit.id)
        show(
            context, CHANNEL_SENDING, "visit:${visit.id}".hashCode(),
            context.getString(R.string.notify_visit_failed_title),
            context.getString(R.string.notify_visit_failed_text, visit.clientName),
            open
        )
    }

    fun taskAssigned(context: Context, task: TaskEntity) {
        val from = task.createdByName?.takeIf { it.isNotBlank() }
        show(
            context, CHANNEL_TASKS, "task:${task.id}".hashCode(),
            if (from != null) context.getString(R.string.notify_task_assigned_by, from)
            else context.getString(R.string.notify_task_assigned),
            task.title,
            Intent(context, TasksActivity::class.java)
        )
    }

    private fun show(context: Context, channel: String, id: Int, title: String, text: String, open: Intent) {
        if (needsPermission(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val pending = PendingIntent.getActivity(
            context, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and here: nothing to show.
        }
    }
}
