package ua.trevogaalarm

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object Net {
    private fun caps(c: Context): NetworkCapabilities? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        val n = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(n)
    }

    /**
     * Реальний інтернет: мережа не лише підключена (Wi-Fi до роутера),
     * а й підтверджена системою (VALIDATED). Wi-Fi без виходу в інтернет
     * раніше вважався «онлайн», тому тривоги не приходили, а попередження не було.
     */
    fun isOnline(c: Context): Boolean {
        val cp = caps(c) ?: return false
        return cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Короткий опис для екрана стану: тип мережі та чи є інтернет. */
    fun describe(c: Context): String {
        val cp = caps(c) ?: return "немає мережі (офлайн-режим)"
        val type = when {
            cp.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            cp.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобільна мережа"
            cp.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "мережа"
        }
        val ok = cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (ok) "є ($type)" else "$type підключено, але інтернету немає (офлайн-режим)"
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
}

class AlertListener : NotificationListenerService() {

    companion object {
        /** true, поки система тримає сервіс підключеним. Скидається, якщо процес був убитий. */
        @Volatile var connected = false
    }

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val h = Handler(Looper.getMainLooper())

    // Перевірка мережі з паузою: перемикання Wi-Fi / мобільна мережа і перевірка
    // підключення системою займають кілька секунд, тому одразу не сповіщаємо.
    private val offlineCheck = Runnable {
        val c = applicationContext
        if (!Net.isOnline(c) && Store.str(c, "offline", "0") != "1") {
            Store.setStr(c, "offline", "1")
            Notif.net(c, android.R.drawable.ic_dialog_alert, "Немає інтернету", "Ви в офлайн-режимі, дані не оновлюються")
        }
    }

    private fun checkSoon() {
        h.removeCallbacks(offlineCheck)
        h.postDelayed(offlineCheck, 5000)
    }

    private fun backOnline() {
        val c = applicationContext
        h.removeCallbacks(offlineCheck)
        if (Store.str(c, "offline", "0") == "1") {
            Store.setStr(c, "offline", "0")
            Notif.net(c, android.R.drawable.ic_dialog_info, "Інтернет", "З’єднання відновлено")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            // onAvailable лише означає, що мережа з'явилась (Wi-Fi до роутера),
            // а не що є інтернет, тому вирішуємо за onCapabilitiesChanged.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ok) backOnline() else checkSoon()
            }

            override fun onLost(network: Network) {
                checkSoon()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        h.removeCallbacks(offlineCheck)
        try {
            netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (e: Exception) {
        }
        netCb = null
        // Без цього після відключення система не підключає сервіс знову і тривоги перестають читатися.
        try {
            requestRebind(ComponentName(this, AlertListener::class.java))
        } catch (e: Exception) {
        }
        super.onListenerDisconnected()
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
        val parts = ArrayList<CharSequence>()
        listOf(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_TITLE_BIG),
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
            ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
            sbn.notification.tickerText
        ).forEach { if (it != null) parts.add(it) }
        // Багато застосунків кладуть текст у список рядків (InboxStyle)
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts.add(it) }
        // Нерозривні/подвійні пробіли ламали збіг фраз на кшталт «повітряна тривога»
        val text = parts.joinToString(" ").lowercase().replace(Regex("[\\s\\u00A0]+"), " ")

        Store.setStr(c, "last", label + ": " + text)

        if (!regionOk(c, text)) return

        // сначала проверяем отбой: в его тексте тоже может быть слово «тривога»
        if (Store.words(c, "end").any { text.contains(it) }) {
            onEnd(c)
        } else if (Store.words(c, "start").any { text.contains(it) }) {
            Store.setAlert(c, true)
        }
    }

    // Если выбран регион или районы/громады, считаем только уведомления, где они упомянуты.
    private fun regionOk(c: Context, text: String): Boolean {
        val idx = Store.str(c, "obl", "0").toIntOrNull() ?: 0
        val words = ArrayList<String>()
        if (idx > 0 && idx < Regions.stems.size) words.addAll(Regions.stems[idx].split(","))
        words.addAll(Store.str(c, "rai", "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        if (words.isEmpty()) return true
        return words.any { text.contains(it) }
    }

    private fun onEnd(c: Context) {
        Store.setAlert(c, false)
        if (Store.pending(c) && Store.enabled(c)) Scheduler.ringSoon(c)
    }
}
