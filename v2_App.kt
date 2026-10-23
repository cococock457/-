package ua.trevogaalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    // k = "start" (слова початку тривоги) або "end" (слова відбою)
    fun words(c: Context, k: String): List<String> {
        val d = if (k == "start") "повітряна тривога,воздушная тревога" else "відбій,отбой"
        return str(c, k, d).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}

object Notif {
    private const val CH_ALERT = "alarm_v2"
    private const val CH_INFO = "info_v2"

    private fun channels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        val a = NotificationChannel(CH_ALERT, "Будильник", NotificationManager.IMPORTANCE_HIGH)
        a.setSound(null, null)
        nm.createNotificationChannel(a)
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Стан", NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    private fun builder(c: Context, ch: String): Notification.Builder {
        channels(c)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else Notification.Builder(c)
    }

    private fun open(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun net(c: Context, icon: Int, title: String, text: String) {
        val n = builder(c, CH_INFO).setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setContentIntent(open(c)).setAutoCancel(true).build()
        nm(c).notify(2, n)
    }

    fun waiting(c: Context) {
        val n = builder(c, CH_INFO).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Будильник чекає")
            .setContentText("Дзвінок пролунає після відбою тривоги")
            .setContentIntent(open(c)).setOngoing(true).build()
        nm(c).notify(3, n)
    }

    fun cancelWaiting(c: Context) { nm(c).cancel(3) }

    @Suppress("DEPRECATION")
    fun ring(c: Context): Notification {
        val stop = PendingIntent.getService(
            c, 1, Intent(c, RingService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder(c, CH_ALERT).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Будильник")
            .setContentText("Тривоги немає. Час вставати")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open(c))
            .setFullScreenIntent(open(c), true)
            .addAction(android.R.drawable.ic_media_pause, "Вимкнути", stop)
            .setOngoing(true)
            .build()
    }
}

object Scheduler {
    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pi(c: Context, kind: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).putExtra("kind", kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun show(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleNext(c: Context) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, Store.hour(c))
        cal.set(Calendar.MINUTE, Store.minute(c))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show(c)), pi(c, "daily", 10))
    }

    fun cancel(c: Context) { am(c).cancel(pi(c, "daily", 10)) }

    // дзвінок за кілька секунд (після відбою тривоги)
    fun ringSoon(c: Context) {
        am(c).setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1500, show(c)),
            pi(c, "ring", 11)
        )
    }
}

fun startRing(c: Context) {
    val i = Intent(c, RingService::class.java).setAction("start")
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra("kind") ?: "daily"
        if (kind == "daily") {
            if (!Store.enabled(c)) return
            Scheduler.scheduleNext(c) // наступний день
            if (Store.alert(c)) {
                // тривога триває: чекаємо відбою
                Store.setPending(c, true)
                Notif.waiting(c)
                Widget.update(c)
                return
            }
        }
        Store.setPending(c, false)
        Notif.cancelWaiting(c)
        Widget.update(c)
        startRing(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.enabled(c)) Scheduler.scheduleNext(c)
        Widget.update(c)
    }
}

class RingService : Service() {
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") {
            stopRing()
            return START_NOT_STICKY
        }
        val n = Notif.ring(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        startSound()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ stopRing() }, 5 * 60 * 1000L) // автоматично вимикається через 5 хвилин
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startSound() {
        if (mp != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val p = MediaPlayer()
            p.setDataSource(this, uri)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.isLooping = true
            p.prepare()
            p.start()
            mp = p
        } catch (e: Exception) {
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 600, 400)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                v.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
        }
    }

    private fun stopRing() {
        h.removeCallbacksAndMessages(null)
        try { mp?.stop() } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        try { getSystemService(Vibrator::class.java).cancel() } catch (e: Exception) { }
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        super.onDestroy()
    }
}

class Widget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        m.updateAppWidget(ids, build(c))
    }

    companion object {
        private fun p2(n: Int) = if (n < 10) "0$n" else n.toString()

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget)
            val alert = if (Store.alert(c)) "ТАК" else "ні"
            val on = if (Store.enabled(c)) "увімк." else "вимк."
            v.setTextViewText(
                R.id.w_text,
                "Тривога: $alert\nБудильник ${p2(Store.hour(c))}:${p2(Store.minute(c))} ($on)"
            )
            val pi = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, pi)
            return v
        }

        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, Widget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, build(c))
        }

        fun pin(c: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val m = c.getSystemService(AppWidgetManager::class.java)
            if (!m.isRequestPinAppWidgetSupported) return false
            return m.requestPinAppWidget(ComponentName(c, Widget::class.java), null, null)
        }
    }
}

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

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var timeBtn: Button
    private var dark = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    private fun p2(n: Int) = if (n < 10) "0$n" else n.toString()
    private fun timeText() = "Час будильника: " + p2(Store.hour(this)) + ":" + p2(Store.minute(this))

    override fun onCreate(b: Bundle?) {
        // тема: збережений вибір, а якщо його немає, то як у системі
        val dm = Store.str(this, "dark", "")
        dark = if (dm.isEmpty()) {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            dm == "1"
        }
        setTheme(if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)
        super.onCreate(b)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // якщо система зупинила слухача, просимо перезапустити (тоді знову працює перевірка тривоги)
        try {
            NotificationListenerService.requestRebind(ComponentName(this, AlertListener::class.java))
        } catch (e: Exception) {
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        fun btn(t: String, f: () -> Unit): Button {
            val bt = Button(this)
            bt.text = t
            bt.setOnClickListener { f() }
            root.addView(bt)
            return bt
        }

        fun field(label: String, key: String, def: String): EditText {
            val tv = TextView(this)
            tv.text = label
            root.addView(tv)
            val e = EditText(this)
            e.setText(Store.str(this, key, def))
            root.addView(e)
            return e
        }

        status = TextView(this)
        status.textSize = 15f
        root.addView(status)

        btn(if (dark) "Світла тема" else "Темна тема") {
            Store.setStr(this, "dark", if (dark) "0" else "1")
            recreate()
        }

        btn("Карта тривог") {
            val names = arrayOf<CharSequence>("alerts.in.ua", "map.ukrainealarm.com")
            val urls = arrayOf("https://alerts.in.ua/", "https://map.ukrainealarm.com/")
            AlertDialog.Builder(this)
                .setTitle("Оберіть карту")
                .setItems(names) { _, i ->
                    startActivity(Intent(this, MapActivity::class.java).putExtra("url", urls[i]))
                }
                .show()
        }

        timeBtn = btn(timeText()) {
            TimePickerDialog(this, { _, hh, mm ->
                Store.setTime(this, hh, mm)
                timeBtn.text = timeText()
                if (Store.enabled(this)) Scheduler.scheduleNext(this)
                refresh()
            }, Store.hour(this), Store.minute(this), true).show()
        }

        val sw = Switch(this)
        sw.text = "Будильник увімкнено"
        sw.isChecked = Store.enabled(this)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setEnabled(this, on)
            if (on) {
                Scheduler.scheduleNext(this)
            } else {
                Scheduler.cancel(this)
                Store.setPending(this, false)
                Notif.cancelWaiting(this)
            }
            refresh()
        }
        root.addView(sw)

        btn("1. Надати доступ до сповіщень") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val regionLabel = TextView(this)
        regionLabel.text = "Область (враховувати тривоги лише цього регіону)"
        regionLabel.setPadding(0, pad, 0, 0)
        root.addView(regionLabel)

        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Regions.names)
        sp.setSelection(Store.str(this, "obl", "0").toIntOrNull() ?: 0)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Store.setStr(this@MainActivity, "obl", position.toString())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(sp)

        val fRai = field("Район або громада (слова через кому, необов’язково)", "rai", "")
        val fApp = field("Назва застосунку тривог (частина назви)", "app", "тривог")
        val fStart = field("Слова початку тривоги (через кому)", "start", "повітряна тривога,воздушная тревога")
        val fEnd = field("Слова відбою (через кому)", "end", "відбій,отбой")

        btn("Зберегти") {
            Store.setStr(this, "rai", fRai.text.toString())
            Store.setStr(this, "app", fApp.text.toString())
            Store.setStr(this, "start", fStart.text.toString())
            Store.setStr(this, "end", fEnd.text.toString())
            Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
            refresh()
        }

        btn("Тривога зараз: перемкнути вручну") {
            val now = !Store.alert(this)
            Store.setAlert(this, now)
            if (!now && Store.pending(this) && Store.enabled(this)) Scheduler.ringSoon(this)
            refresh()
        }

        btn("Вимкнути дзвінок") {
            startService(Intent(this, RingService::class.java).setAction("stop"))
        }

        btn("2. Дозволити роботу у фоні (без економії батареї)") {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Вже дозволено", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + packageName))
                )
            }
        }

        btn("Додати віджет на екран") {
            if (!Widget.pin(this)) {
                Toast.makeText(
                    this,
                    "Додайте вручну: утримуйте головний екран → Віджети → Тривога-будильник",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val hint = TextView(this)
        hint.text = "Порада: вимкніть для цього застосунку економію батареї, інакше система може зупинити читання сповіщень."
        hint.setPadding(0, pad, 0, 0)
        root.addView(hint)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val l = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val access = l.contains(packageName)
        status.text = "Тривога зараз: " + (if (Store.alert(this)) "ТАК" else "ні") +
            "\nБудильник: " + (if (Store.enabled(this)) "увімк." else "вимк.") +
            "\nЧекає відбою: " + (if (Store.pending(this)) "так" else "ні") +
            "\nДоступ до сповіщень: " + (if (access) "є" else "НЕМАЄ") +
            "\nІнтернет: " + (if (Net.isOnline(this)) "є" else "немає (офлайн-режим)") +
            "\nAPI ukrainealarm: " + Store.str(this, "api", "—") +
            "\nОстаннє сповіщення від застосунку тривог:\n" + Store.str(this, "last", "—")
        Widget.update(this)
    }
}

class MapActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        setContentView(web)
        if (!Net.isOnline(this)) {
            Toast.makeText(this, "Ви в офлайн-режимі, карта недоступна", Toast.LENGTH_LONG).show()
        }
        web.loadUrl(intent.getStringExtra("url") ?: "https://alerts.in.ua/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
