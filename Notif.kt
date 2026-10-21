package ua.trevogaalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notif {
    const val CH_RING = "ring"
    const val CH_WAIT = "wait"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, "Будильник", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WAIT, "Ожидание отбоя", NotificationManager.IMPORTANCE_LOW))
    }

    fun waiting(c: Context) {
        channels(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH_WAIT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Будильник ждёт отбоя")
            .setContentText("Зазвонит сразу после отбоя тревоги")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(2, n)
    }

    fun cancelWaiting(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(2)
    }
}
