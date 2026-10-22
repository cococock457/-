package ua.trevogaalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {

    private const val KIND_MAIN = 0
    private const val KIND_RING = 1
    private const val KIND_SNOOZE = 2

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(c: Context, kind: Int, t: Long): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        i.putExtra("ring", kind == KIND_RING)
        i.putExtra("snooze", kind == KIND_SNOOZE)
        i.putExtra("t", t)
        val code = when (kind) { KIND_RING -> 2; KIND_SNOOZE -> 4; else -> 1 }
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(c: Context, t: Long, kind: Int) {
        val show = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi(c, kind, t))
    }

    /** Найближче спрацювання будильника строго після моменту from. */
    fun nextTime(a: Alarm, from: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.HOUR_OF_DAY, a.h)
        cal.set(Calendar.MINUTE, a.m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var guard = 0
        while ((cal.timeInMillis <= from || !dayOk(a, cal)) && guard < 9) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return cal.timeInMillis
    }

    private fun dayOk(a: Alarm, cal: Calendar): Boolean {
        if (a.mode != 1) return true
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d >= Calendar.MONDAY && d <= Calendar.FRIDAY
    }

    /** Час найближчого увімкненого будильника або null. */
    fun nextAt(c: Context): Long? {
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE
        for (a in Store.alarms(c)) if (a.on) best = minOf(best, nextTime(a, now))
        return if (best == Long.MAX_VALUE) null else best
    }

    /** Ставить системний будильник на найближчий із усіх увімкнених. */
    fun scheduleNext(c: Context) {
        val t = nextAt(c)
        if (t == null) cancel(c) else setAt(c, t, KIND_MAIN)
        AlarmWidget.updateAll(c)
    }

    /**
     * Спрацював основний будильник у момент t: вимикаємо одноразові,
     * які збіглися з цим часом, і заводимо наступний.
     */
    fun markFired(c: Context, t: Long) {
        val list = Store.alarms(c)
        var changed = false
        for (a in list) {
            if (!a.on) continue
            val n = nextTime(a, t - 90_000L)
            if (n <= t + 30_000L && a.mode == 0) {
                a.on = false
                changed = true
            }
        }
        if (changed) Store.saveAlarms(c, list)
        scheduleNext(c)
    }

    /** Звонок через секунду (після відбою). Через будильник, щоб система дозволила запуск звуку. */
    fun ringSoon(c: Context) = setAt(c, System.currentTimeMillis() + 1000, KIND_RING)

    /** Відкласти будильник на minutes хвилин. */
    fun snoozeFor(c: Context, minutes: Int) {
        val t = System.currentTimeMillis() + minutes * 60_000L
        Store.setSnoozeUntil(c, t)
        setAt(c, t, KIND_SNOOZE)
    }

    fun cancelSnooze(c: Context) {
        am(c).cancel(pi(c, KIND_SNOOZE, 0L))
        Store.setSnoozeUntil(c, 0L)
    }

    fun cancel(c: Context) { am(c).cancel(pi(c, KIND_MAIN, 0L)) }

}
