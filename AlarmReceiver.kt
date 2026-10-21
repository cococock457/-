package ua.trevogaalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("ring", false)) { ring(c); return }
        // сработало время будильника: заводим следующее на завтра
        Scheduler.scheduleNext(c)
        if (Store.alert(c)) {
            Store.setPending(c, true)   // тревога идёт: ждём отбоя
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
