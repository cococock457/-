package ua.trevogaalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Один будильник. mode: 0 = одноразовий, 1 = будні (Пн–Пт), 2 = щодня. */
class Alarm(val id: Int, var h: Int, var m: Int, var mode: Int, var on: Boolean)

object Store {

    private fun p(c: Context) = c.getSharedPreferences("s", Context.MODE_PRIVATE)

    // ---------- будильники ----------

    fun alarms(c: Context): MutableList<Alarm> {
        val list = ArrayList<Alarm>()
        val raw = p(c).getString("alarms", null)
        if (raw == null) {
            // перший запуск після оновлення: переносимо старий єдиний будильник
            val old = Alarm(1, p(c).getInt("h", 8), p(c).getInt("m", 0), 2, p(c).getBoolean("on", false))
            list.add(old)
            saveAlarms(c, list)
            return list
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("mode"), o.getBoolean("on")))
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun saveAlarms(c: Context, list: List<Alarm>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("h", a.h)
            o.put("m", a.m)
            o.put("mode", a.mode)
            o.put("on", a.on)
            arr.put(o)
        }
        p(c).edit().putString("alarms", arr.toString()).apply()
        AlarmWidget.updateAll(c)
    }

    /** true, якщо увімкнений хоча б один будильник. */
    fun enabled(c: Context): Boolean = alarms(c).any { it.on }

    // ---------- стан тривоги ----------

    fun alert(c: Context) = p(c).getBoolean("alert", false)

    fun setAlert(c: Context, v: Boolean) {
        val changed = alert(c) != v
        p(c).edit().putBoolean("alert", v).apply()
        if (changed) AlarmWidget.updateAll(c)
    }

    fun pending(c: Context) = p(c).getBoolean("pending", false)

    fun setPending(c: Context, v: Boolean) { p(c).edit().putBoolean("pending", v).apply() }

    // ---------- дзвінок і відкладення ----------

    fun ringing(c: Context) = p(c).getBoolean("ringing", false)

    fun setRinging(c: Context, v: Boolean) { p(c).edit().putBoolean("ringing", v).apply() }

    /** На скільки хвилин відкладається будильник. */
    fun snoozeMin(c: Context): Int = (str(c, "snooze", "10").toIntOrNull() ?: 10).coerceIn(1, 120)

    /** Час (мс), на який відкладено будильник, або 0. */
    fun snoozeUntil(c: Context): Long = p(c).getLong("snoozeUntil", 0L)

    fun setSnoozeUntil(c: Context, t: Long) { p(c).edit().putLong("snoozeUntil", t).apply() }

    // ---------- рядки налаштувань ----------

    fun str(c: Context, k: String, d: String): String = p(c).getString(k, d) ?: d

    fun setStr(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }

    // k = "start" (слова начала тривоги) или "end" (слова отбоя)

    fun words(c: Context, k: String): List<String> {

        val d = if (k == "start") "повітряна тривога,воздушная тревога" else "відбій,отбой"

        return str(c, k, d).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    }

}
