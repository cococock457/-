package ua.trevogaalarm

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var timeBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    private fun p2(n: Int) = if (n < 10) "0$n" else n.toString()
    private fun timeText() = "Время будильника: " + p2(Store.hour(this)) + ":" + p2(Store.minute(this))

    override fun onCreate(b: Bundle?) {
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

        timeBtn = btn(timeText()) {
            TimePickerDialog(this, { _, hh, mm ->
                Store.setTime(this, hh, mm)
                timeBtn.text = timeText()
                if (Store.enabled(this)) Scheduler.scheduleNext(this)
                refresh()
            }, Store.hour(this), Store.minute(this), true).show()
        }

        val sw = Switch(this)
        sw.text = "Будильник включён"
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

        btn("1. Дать доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val fApp = field("Имя приложения тревог (часть названия)", "app", "тривог")
        val fStart = field("Слова начала тревоги (через запятую)", "start", "повітряна тривога,воздушная тревога")
        val fEnd = field("Слова отбоя (через запятую)", "end", "відбій,отбой")
        btn("Сохранить слова") {
            Store.setStr(this, "app", fApp.text.toString())
            Store.setStr(this, "start", fStart.text.toString())
            Store.setStr(this, "end", fEnd.text.toString())
            refresh()
        }

        btn("Тревога сейчас: переключить вручную") {
            val now = !Store.alert(this)
            Store.setAlert(this, now)
            if (!now && Store.pending(this) && Store.enabled(this)) Scheduler.ringSoon(this)
            refresh()
        }

        btn("Выключить звонок") {
            startService(Intent(this, RingService::class.java).setAction("stop"))
        }

        val hint = TextView(this)
        hint.text = "Совет: отключите для этого приложения экономию батареи, иначе система может остановить чтение уведомлений."
        hint.setPadding(0, pad, 0, 0)
        root.addView(hint)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val l = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val access = l.contains(packageName)
        status.text = "Тревога сейчас: " + (if (Store.alert(this)) "ДА" else "нет") +
            "\nБудильник: " + (if (Store.enabled(this)) "вкл" else "выкл") +
            "\nЖдёт отбоя: " + (if (Store.pending(this)) "да" else "нет") +
            "\nДоступ к уведомлениям: " + (if (access) "есть" else "НЕТ") +
            "\nПоследнее уведомление от приложения тревог:\n" + Store.str(this, "last", "—")
    }
}
