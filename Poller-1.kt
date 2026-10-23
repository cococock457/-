package ua.trevogaalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Єдине місце, де змінюється стан «тривога зараз»: із сповіщень, з API або вручну. */
object Alerts {

    /** Слова вибраної області та районів/громад; порожньо = без фільтра. */
    fun regionWords(c: Context): List<String> {
        val idx = Store.str(c, "obl", "0").toIntOrNull() ?: 0
        val words = ArrayList<String>()
        if (idx > 0 && idx < Regions.stems.size) words.addAll(Regions.stems[idx].split(","))
        words.addAll(districtWords(c))
        return words
    }

    /** Слова громади, які користувач ввів вручну (список громад завеликий для меню). */
    fun districtWords(c: Context): List<String> =
        Store.str(c, "gro", "").split(",")
            .map { it.trim().lowercase().replace('’', '\'').replace('ʼ', '\'') }
            .filter { it.isNotEmpty() }

    /** Вибрано район (зі списку) або вказано громаду. */
    fun hasDistrict(c: Context): Boolean =
        Store.str(c, "rai_name", "").isNotEmpty() || districtWords(c).isNotEmpty()

    /** Чи стосується текст сповіщення вибраного району або громади. */
    fun districtMatch(c: Context, text: String): Boolean {
        val name = Store.str(c, "rai_name", "")
        if (name.isNotEmpty() && Raions.matches(name, text)) return true
        return districtWords(c).any { text.contains(it) }
    }

    fun set(c: Context, v: Boolean) {
        val was = Store.alert(c)
        if (v == was) return
        Store.setAlert(c, v)
        // відбій: якщо будильник чекав на нього, дзвонимо
        if (!v && Store.pending(c) && Store.enabled(c)) Scheduler.ringSoon(c)
    }
}

/** Перевірка тривоги через API alerts.in.ua (потрібен токен). */
object Poller {

    private const val API_URL = "https://api.alerts.in.ua/v1/alerts/active.json"

    fun token(c: Context): String = Store.str(c, "token", "").trim()

    /** Опитування увімкнене: є токен і перемикач «перевіряти раз на хвилину». */
    fun enabled(c: Context): Boolean = false // перевірка через API без токена не використовується

    private fun fail(c: Context, msg: String): Boolean {
        Store.setStr(c, "poll_err", msg)
        return false
    }

    /** Один запит до API. Блокує потік: викликати не з головного. */
    fun pollNow(c: Context): Boolean {
        if (!enabled(c)) return false
        var conn: HttpURLConnection? = null
        try {
            conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("Authorization", "Bearer " + token(c))
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) return fail(c, "токен не прийнято (HTTP $code)")
            if (code != 200) return fail(c, "помилка сервера (HTTP $code)")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(body).getJSONArray("alerts")
            var active = false
            for (i in 0 until arr.length()) {
                if (relevant(c, arr.getJSONObject(i))) { active = true; break }
            }
            Store.setStr(c, "poll_ok", System.currentTimeMillis().toString())
            Store.setStr(c, "poll_err", "")
            Alerts.set(c, active)
            return true
        } catch (e: Exception) {
            return fail(c, "немає зв’язку (" + e.javaClass.simpleName + ")")
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** Чи стосується цей запис API вибраного регіону і чи це повітряна тривога. */
    private fun relevant(c: Context, o: JSONObject): Boolean {
        if (o.optString("alert_type", "air_raid") != "air_raid") return false
        if (!o.isNull("finished_at") && o.optString("finished_at").isNotEmpty()) return false
        val text = (o.optString("location_oblast") + " " + o.optString("location_title")).lowercase()
        val districts = Alerts.districtWords(c)
        if (districts.isNotEmpty()) {
            // вказано район/громаду: враховуємо тривогу будь-якого рівня, де вони згадані
            return districts.any { text.contains(it) }
        }
        // без районів: тривога на рівні області (або міста); тривоги окремих районів не рахуємо
        val type = o.optString("location_type", "oblast")
        if (type != "oblast" && type != "city") return false
        val words = Alerts.regionWords(c)
        return words.isEmpty() || words.any { text.contains(it) }
    }

    /** Опитує, лише якщо останній успішний запит старший за maxAgeMs. */
    fun pollIfStale(c: Context, maxAgeMs: Long) {
        if (!enabled(c)) return
        val last = Store.str(c, "poll_ok", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last >= maxAgeMs) pollNow(c)
    }

    // ---------- розклад: запит раз на хвилину ----------

    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 5, Intent(c, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Раз на кілька хвилин перевіряє, що читання сповіщень живе (токен не потрібен). */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + 5 * 60_000L
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        }
    }
}

class PollReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        try {
            val granted = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
                ?.contains(app.packageName) == true
            if (granted && !AlertListener.connected) {
                NotificationListenerService.requestRebind(ComponentName(app, AlertListener::class.java))
            }
        } catch (e: Exception) {
        }
        Poller.schedule(app)
    }
}

/** Райони України (після реформи 2020) за областями; порядок збігається з Regions.names. */
object Raions {
    private val data = arrayOf(
        "",
        "Вінницький,Гайсинський,Жмеринський,Могилів-Подільський,Тульчинський,Хмільницький",
        "Володимирський,Камінь-Каширський,Ковельський,Луцький",
        "Дніпровський,Кам'янський,Криворізький,Нікопольський,Павлоградський,Самарівський,Синельниківський",
        "Бахмутський,Волноваський,Горлівський,Донецький,Кальміуський,Краматорський,Маріупольський,Покровський",
        "Бердичівський,Житомирський,Коростенський,Новоград-Волинський",
        "Берегівський,Мукачівський,Рахівський,Тячівський,Ужгородський,Хустський",
        "Бердянський,Василівський,Запорізький,Мелітопольський,Пологівський",
        "Верховинський,Івано-Франківський,Калуський,Коломийський,Косівський,Надвірнянський",
        "Білоцерківський,Бориспільський,Броварський,Бучанський,Вишгородський,Обухівський,Фастівський",
        "",
        "Голованівський,Кропивницький,Новоукраїнський,Олександрійський",
        "Алчевський,Довжанський,Луганський,Ровеньківський,Свердловський,Сєвєродонецький,Старобільський,Щастинський",
        "Дрогобицький,Золочівський,Львівський,Самбірський,Стрийський,Шептицький/Червоноградський,Яворівський",
        "Баштанський,Вознесенський,Миколаївський,Первомайський",
        "Березівський,Білгород-Дністровський,Болградський,Ізмаїльський,Одеський,Подільський,Роздільнянський",
        "Кременчуцький,Лубенський,Миргородський,Полтавський",
        "Вараський,Дубенський,Рівненський,Сарненський",
        "Конотопський,Охтирський,Роменський,Сумський,Шосткинський",
        "Кременецький,Тернопільський,Чортківський",
        "Берестинський/Красноградський,Богодухівський,Ізюмський,Куп'янський,Лозівський,Харківський,Чугуївський",
        "Бериславський,Генічеський,Каховський,Скадовський,Херсонський",
        "Кам'янець-Подільський,Хмельницький,Шепетівський",
        "Звенигородський,Золотоніський,Уманський,Черкаський",
        "Вижницький,Дністровський,Чернівецький",
        "Корюківський,Ніжинський,Новгород-Сіверський,Прилуцький,Чернігівський",
        ""
    )

    /** Назви районів для меню (номер області — як у Regions.names). */
    fun names(obl: Int): List<String> {
        if (obl <= 0 || obl >= data.size || data[obl].isEmpty()) return emptyList()
        return data[obl].split(",").map { it.replace("/", " / ") + " район" }
    }

    private fun stems(alt: String): List<String> {
        var u = alt.trim().lowercase().replace('’', '\'').replace('ʼ', '\'')
        if (u.endsWith("ий") || u.endsWith("ій")) u = u.dropLast(2)
        val ru = u.replace("ськ", "ск").replace("і", "и").replace("ї", "и")
        return if (ru == u) listOf(u) else listOf(u, ru)
    }

    /** Чи згадано цей район у тексті (назва + слово «район», у будь-якому відмінку). */
    fun matches(name: String, text: String): Boolean {
        val base = name.removeSuffix(" район")
        for (alt in base.split("/")) {
            for (st in stems(alt)) {
                if (Regex(Regex.escape(st) + "[а-яіїєґ']{0,4}\\s+(район|р-н)").containsMatchIn(text)) return true
            }
        }
        return false
    }
}
