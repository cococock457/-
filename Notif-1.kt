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
    const val CH_NET = "net"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, "Будильник", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WAIT, "Очікування відбою", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_NET, "Стан мережі", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun waiting(c: Context) {
        channels(c)
        val n = Notification.Builder(c, CH_WAIT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Будильник чекає на відбій")
            .setContentText("Задзвонить одразу після відбою тривоги")
            .setContentIntent(open(c))
            .setOngoing(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(2, n)
    }

    fun cancelWaiting(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(2)
    }

    /** Повідомлення про стан мережі (одне й те саме місце: нове замінює старе). */
    fun net(c: Context, icon: Int, title: String, text: String) {
        channels(c)
        val n = Notification.Builder(c, CH_NET)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(3, n)
    }
}
