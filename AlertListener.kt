package ua.trevogaalarm

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AlertListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = applicationContext
        if (sbn.packageName == c.packageName) return

        val filter = Store.str(c, "app", "тривог").trim().lowercase()
        if (filter.isEmpty()) return

        val label = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { "" }
        if (!(sbn.packageName.lowercase().contains(filter) || label.lowercase().contains(filter))) return

        val ex = sbn.notification.extras
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ).joinToString(" ").lowercase()

        Store.setStr(c, "last", label + ": " + text)

        // сначала проверяем отбой: в его тексте тоже может быть слово «тривога»
        if (Store.words(c, "end").any { text.contains(it) }) {
            onEnd(c)
        } else if (Store.words(c, "start").any { text.contains(it) }) {
            Store.setAlert(c, true)
        }
    }

    private fun onEnd(c: Context) {
        Store.setAlert(c, false)
        if (Store.pending(c) && Store.enabled(c)) Scheduler.ringSoon(c)
    }
}
