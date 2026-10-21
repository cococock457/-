package ua.trevogaalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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

        btn("2. Вимкнути економію батареї для застосунку") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
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
        // Доступ дано, але система не підключила сервіс (після оновлення, вбивства процесу): просимо перепідключити
        if (access && !AlertListener.connected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, AlertListener::class.java))
            } catch (e: Exception) {
            }
        }
        status.text = "Тривога зараз: " + (if (Store.alert(this)) "ТАК" else "ні") +
            "\nБудильник: " + (if (Store.enabled(this)) "увімк." else "вимк.") +
            "\nЧекає відбою: " + (if (Store.pending(this)) "так" else "ні") +
            "\nДоступ до сповіщень: " + (if (access) "є" else "НЕМАЄ") +
            "\nЧитання сповіщень: " + (if (AlertListener.connected) "працює" else "НЕ підключено") +
            "\nІнтернет: " + Net.describe(this) +
            "\nОстаннє сповіщення від застосунку тривог:\n" + Store.str(this, "last", "—")
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
