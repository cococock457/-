package ua.trevogaalarm

import android.content.Context

object Store {
    private fun p(c: Context) = c.getSharedPreferences("s", Context.MODE_PRIVATE)

    fun hour(c: Context) = p(c).getInt("h", 8)
    fun minute(c: Context) = p(c).getInt("m", 0)
    fun setTime(c: Context, h: Int, m: Int) { p(c).edit().putInt("h", h).putInt("m", m).apply() }

    fun enabled(c: Context) = p(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) { p(c).edit().putBoolean("on", v).apply() }

    fun alert(c: Context) = p(c).getBoolean("alert", false)
    fun setAlert(c: Context, v: Boolean) { p(c).edit().putBoolean("alert", v).apply() }

    fun pending(c: Context) = p(c).getBoolean("pending", false)
    fun setPending(c: Context, v: Boolean) { p(c).edit().putBoolean("pending", v).apply() }

    fun str(c: Context, k: String, d: String): String = p(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }

    // k = "start" (слова начала тревоги) или "end" (слова отбоя)
    fun words(c: Context, k: String): List<String> {
        val d = if (k == "start") "повітряна тривога,воздушная тревога" else "відбій,отбой"
        return str(c, k, d).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}
