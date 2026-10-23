package ua.trevogaalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.scheduleNext(c)
        // відкладений будильник після перезавантаження ставимо знову
        val until = Store.snoozeUntil(c)
        if (until > System.currentTimeMillis()) {
            Scheduler.snoozeFor(c, ((until - System.currentTimeMillis()) / 60_000L).toInt() + 1)
        } else if (until != 0L) {
            Store.setSnoozeUntil(c, 0L)
        }
        Poller.schedule(c)
    }
}
