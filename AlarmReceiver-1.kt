package ua.trevogaalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("ring", false)) { ring(c); return }

        val snooze = i.getBooleanExtra("snooze", false)
        val t = i.getLongExtra("t", System.currentTimeMillis())
        val app = c.applicationContext
        val pr = goAsync()
        Thread {
            try {
                if (snooze) {
                    Store.setSnoozeUntil(app, 0L)
                } else {
                    // одноразові вимикаємо, наступний будильник заводимо
                    Scheduler.markFired(app, t)
                }
                // свіжа перевірка тривоги перед рішенням (якщо вказано токен API)
                Poller.pollIfStale(app, 90_000L)
                decide(app)
            } catch (e: Exception) {
                try { ring(app) } catch (e2: Exception) {}
            } finally {
                pr.finish()
            }
        }.start()
    }

    private fun decide(c: Context) {
        if (Store.alert(c)) {
            Store.setPending(c, true)   // тривога йде: чекаємо відбою
            Notif.waiting(c)
        } else {
            ring(c)
        }
    }

    private fun ring(c: Context) {
        Store.setPending(c, false)
        Notif.cancelWaiting(c)
        c.startForegroundService(Intent(c, RingService::class.java))
    }
}
