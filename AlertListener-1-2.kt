package ua.trevogaalarm

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Net {
    fun isOnline(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

object Regions {
    val names = arrayOf(
        "Без фільтра (усі регіони)",
        "Вінницька область",
        "Волинська область",
        "Дніпропетровська область",
        "Донецька область",
        "Житомирська область",
        "Закарпатська область",
        "Запорізька область",
        "Івано-Франківська область",
        "Київська область",
        "м. Київ",
        "Кіровоградська область",
        "Луганська область",
        "Львівська область",
        "Миколаївська область",
        "Одеська область",
        "Полтавська область",
        "Рівненська область",
        "Сумська область",
        "Тернопільська область",
        "Харківська область",
        "Херсонська область",
        "Хмельницька область",
        "Черкаська область",
        "Чернівецька область",
        "Чернігівська область",
        "АР Крим"
    )

    val stems = arrayOf(
        "",
        "вінницьк,винницк,вінниччин",
        "волинськ,волынск",
        "дніпропетровськ,днепропетровск,дніпропетровщин",
        "донецьк,донецк,донеччин",
        "житомирськ,житомирск,житомирщин",
        "закарпатськ,закарпатск",
        "запорізьк,запорожск,запоріжж",
        "івано-франківськ,ивано-франковск,прикарпат",
        "київськ,киевск,київщин",
        "м. київ,м.київ,києв",
        "кіровоградськ,кировоградск",
        "луганськ,луганск",
        "львівськ,львовск,львівщин",
        "миколаївськ,николаевск",
        "одеськ,одесск,одещин",
        "полтавськ,полтавск,полтавщин",
        "рівненськ,ровенск",
        "сумськ,сумск,сумщин",
        "тернопільськ,тернопольск",
        "харківськ,харьковск,харківщин",
        "херсонськ,херсонск",
        "хмельницьк,хмельницк,хмельниччин",
        "черкаськ,черкасск,черкащин",
        "чернівецьк,черновицк",
        "чернігівськ,черниговск,чернігівщин",
        "крим,крым"
    )

    // слова обраної області + район/громада з налаштувань
    fun words(c: Context): List<String> {
        val idx = Store.str(c, "obl", "0").toIntOrNull() ?: 0
        val w = ArrayList<String>()
        if (idx > 0 && idx < stems.size) w.addAll(stems[idx].split(","))
        w.addAll(Store.str(c, "rai", "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        return w
    }
}

// Перевірка тривоги через api.ukrainealarm.com. Ключ вшивається під час збірки (Secrets.kt).
object Api {
    // true = є повітряна тривога в обраному регіоні, false = немає. При помилці кидає виняток.
    fun check(c: Context): Boolean {
        val con = URL("https://api.ukrainealarm.com/api/v3/alerts").openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10000
            con.readTimeout = 10000
            con.setRequestProperty("Authorization", Secrets.ALERTS_KEY)
            val code = con.responseCode
            if (code != 200) throw Exception("HTTP $code")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val words = Regions.words(c)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val alerts = o.optJSONArray("activeAlerts") ?: continue
                var air = false
                for (j in 0 until alerts.length()) {
                    if (alerts.getJSONObject(j).optString("type") == "AIR") air = true
                }
                if (!air) continue
                if (words.isEmpty()) return true
                val name = o.optString("regionName").lowercase()
                if (words.any { name.contains(it) }) return true
            }
            return false
        } finally {
            con.disconnect()
        }
    }
}

class AlertListener : NotificationListenerService() {

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val h = Handler(Looper.getMainLooper())

    @Volatile private var polling = false
    private var pollThread: Thread? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val c = applicationContext
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Store.str(c, "offline", "0") == "1") {
                    Store.setStr(c, "offline", "0")
                    Notif.net(c, android.R.drawable.ic_dialog_info, "Інтернет", "З’єднання відновлено")
                }
            }

            override fun onLost(network: Network) {
                // невелика пауза, щоб не реагувати на перемикання Wi-Fi / мобільна мережа
                h.postDelayed({
                    if (!Net.isOnline(c) && Store.str(c, "offline", "0") != "1") {
                        Store.setStr(c, "offline", "1")
                        Notif.net(c, android.R.drawable.ic_dialog_alert, "Немає інтернету", "Ви в офлайн-режимі, дані не оновлюються")
                    }
                }, 3000)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
        }
        startPolling(c)
    }

    override fun onListenerDisconnected() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
        try {
            netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (e: Exception) {
        }
        netCb = null
        super.onListenerDisconnected()
    }

    private fun startPolling(c: Context) {
        if (polling) return
        if (Secrets.ALERTS_KEY.isEmpty()) {
            Store.setStr(c, "api", "ключ не задано")
            return
        }
        polling = true
        val t = Thread {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            while (polling) {
                var delay = 15000L // перевірка кожні 15 секунд
                try {
                    if (Net.isOnline(c)) {
                        val now = Api.check(c)
                        Store.setStr(c, "api", "ок " + fmt.format(Date()) + ", тривога: " + (if (now) "так" else "ні"))
                        val cur = if (now) "1" else "0"
                        // реагуємо тільки на зміну стану в API (ручне перемикання не скидається щоразу)
                        if (Store.str(c, "apilast", "") != cur) {
                            Store.setStr(c, "apilast", cur)
                            if (now) {
                                Store.setAlert(c, true)
                            } else {
                                onEnd(c)
                            }
                            Widget.update(c)
                        }
                    } else {
                        Store.setStr(c, "api", "немає інтернету " + fmt.format(Date()))
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    delay = 60000L // при помилці або ліміті запитів чекаємо довше
                    Store.setStr(c, "api", "помилка " + fmt.format(Date()) + ": " + (e.message ?: "невідома"))
                }
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.start()
        pollThread = t
    }

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
        if (!regionOk(c, text)) return
        // сначала проверяем отбой: в его тексте тоже может быть слово «тривога»
        if (Store.words(c, "end").any { text.contains(it) }) {
            onEnd(c)
        } else if (Store.words(c, "start").any { text.contains(it) }) {
            Store.setAlert(c, true)
        }
        Widget.update(c)
    }

    // Если выбран регион или районы/громады, считаем только уведомления, где они упомянуты.
    private fun regionOk(c: Context, text: String): Boolean {
        val words = Regions.words(c)
        if (words.isEmpty()) return true
        return words.any { text.contains(it) }
    }

    private fun onEnd(c: Context) {
        Store.setAlert(c, false)
        if (Store.pending(c) && Store.enabled(c)) Scheduler.ringSoon(c)
    }
}
