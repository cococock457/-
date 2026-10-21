package ua.trevogaalarm

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object Regions {
    val names = arrayOf(
        "Без фильтра (все регионы)",
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
