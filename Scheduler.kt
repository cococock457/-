package ua.trevogaalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {
    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(c: Context, ring: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("ring", ring)
        return PendingIntent.getBroadcast(
            c, if (ring) 2 else 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAt(c: Context, t: Long, ring: Boolean) {
        val show = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi(c, ring))
    }

    /** Ставит будильник на ближайшее наступление выбранного времени. */
    fun scheduleNext(c: Context) {
        if (!Store.enabled(c)) { cancel(c); return }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, Store.hour(c))
        cal.set(Calendar.MINUTE, Store.minute(c))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        setAt(c, cal.timeInMillis, false)
    }

    /** Звонок через секунду (после отбоя). Через будильник, чтобы система разрешила запуск звука. */
    fun ringSoon(c: Context) = setAt(c, System.currentTimeMillis() + 1000, true)

    fun cancel(c: Context) { am(c).cancel(pi(c, false)) }
}
