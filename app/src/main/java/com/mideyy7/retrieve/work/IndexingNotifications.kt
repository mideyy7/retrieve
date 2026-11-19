package com.mideyy7.retrieve.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.mideyy7.retrieve.R

/**
 * The notification shown while a document is indexed. Indexing a long, image-heavy PDF can take
 * longer than the 10 minutes WorkManager allows an ordinary worker, so the worker runs in the
 * foreground (service type dataSync: the app is processing the user's own imported files).
 */
object IndexingNotifications {
    private const val CHANNEL = "indexing"
    const val ID = 1001

    fun foregroundInfo(context: Context, name: String, done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)
        val text = if (total > 0) "Page $done of $total" else "Starting…"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Indexing $name")
            .setContentText(text)
            .setProgress(total.coerceAtLeast(0), done.coerceAtMost(total.coerceAtLeast(0)), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Indexing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while imported files are read and made searchable"
                },
            )
        }
    }
}
