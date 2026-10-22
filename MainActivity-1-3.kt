package ua.trevogaalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private const val MAP_URL = "https://map.ukrainealarm.com/"

private class Perm(
    val icon: String,
    val title: String,
    val desc: String,
    val hint: String,
    val granted: () -> Boolean,
    val request: () -> Unit
)

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // палітра
    private var dark = false
    private var cBg = 0
    private var cCard = 0
    private var cText = 0
    private var cSub = 0
    private var cAcc = 0
    private var cAcc2 = 0
    private var cOk = 0
    private var cBad = 0
    private var cField = 0
    private var cBar = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    // вкладки
    private var cur = 0
    private val pageViews = ArrayList<View>()
    private val tabBox = ArrayList<LinearLayout>()
    private val tabLb = ArrayList<TextView>()

    // вкладка «Будильник»
    private lateinit var timeTv: TextView
    private lateinit var badge: TextView
    private lateinit var pendingTv: TextView
    private lateinit var info: TextView
    private lateinit var banner: LinearLayout

    // вкладка «Мапа»
    private lateinit var mapHost: FrameLayout
    private lateinit var mapBar: ProgressBar
    private lateinit var mapErr: LinearLayout
    private var web: WebView? = null

    // вкладка «Налаштування»
    private lateinit var permBox: LinearLayout
    private lateinit var toneName: TextView

    // майстер дозволів
    private var wizardIdx = -1
    private var wizardWait = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun dpf(n: Int) = n * resources.displayMetrics.density
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    private fun p2(n: Int) = if (n < 10) "0$n" else n.toString()

    private fun shape(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dpf(radiusDp)
        return g
    }

    private fun initColors() {
        if (dark) {
            cBg = Color.parseColor("#15141F"); cCard = Color.parseColor("#211F31")
            cText = Color.parseColor("#F1EFFA"); cSub = Color.parseColor("#A6A3BF")
            cAcc = Color.parseColor("#9D8CFF"); cAcc2 = Color.parseColor("#D46AA8")
            cOk = Color.parseColor("#5DD9A0"); cBad = Color.parseColor("#FF7B7B")
            cField = Color.parseColor("#2C2A42"); cBar = Color.parseColor("#1B1A28")
        } else {
            cBg = Color.parseColor("#F4F1FB"); cCard = Color.parseColor("#FFFFFF")
            cText = Color.parseColor("#2B2A3A"); cSub = Color.parseColor("#7C7A93")
            cAcc = Color.parseColor("#7F6CF2"); cAcc2 = Color.parseColor("#FF9BC4")
            cOk = Color.parseColor("#2FB980"); cBad = Color.parseColor("#F25C6A")
            cField = Color.parseColor("#F0EDF9"); cBar = Color.parseColor("#FFFFFF")
        }
    }

    // ---------- допоміжні елементи інтерфейсу ----------

    private fun col(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun scroller(inner: LinearLayout): ScrollView {
        val pad = dp(16)
        inner.setPadding(pad, dp(18), pad, dp(20))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(inner, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = col()
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.background = shape(cCard, 22)
        c.elevation = dpf(3)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(14)
        parent.addView(c, lp)
        return c
    }

    private fun txt(p: LinearLayout, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        p.addView(t, LinearLayout.LayoutParams(WRAP, WRAP))
        return t
    }

    private fun pill(p: LinearLayout, s: String, filled: Boolean, f: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setTextColor(if (filled) Color.WHITE else cAcc)
        b.background = if (filled) shape(cAcc, 16) else shape(a(cAcc, 38), 16)
        b.setOnClickListener { f() }
        val lp = LinearLayout.LayoutParams(MATCH, dp(48))
        lp.topMargin = dp(10)
        p.addView(b, lp)
        return b
    }

    private fun field(p: LinearLayout, label: String, key: String, def: String): EditText {
        val l = txt(p, label, 12f, cSub)
        l.setPadding(0, dp(12), 0, dp(4))
        val e = EditText(this)
        e.setText(Store.str(this, key, def))
        e.setTextColor(cText)
        e.textSize = 15f
        e.setSingleLine(true)
        e.background = shape(cField, 14)
        e.setPadding(dp(14), dp(10), dp(14), dp(10))
        p.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
        return e
    }

    private fun tintSwitch(s: Switch, onThumb: Int, offThumb: Int, onTrack: Int, offTrack: Int) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(onThumb, offThumb))
        s.trackTintList = ColorStateList(states, intArrayOf(onTrack, offTrack))
    }

    // ---------- життєвий цикл ----------

    override fun onCreate(b: Bundle?) {
        // тема: збережений вибір, а якщо його немає, то як у системі
        val dm = Store.str(this, "dark", "")
        dark = if (dm.isEmpty()) {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            dm == "1"
        }
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(b)
        initColors()

        window.statusBarColor = cBg
        window.navigationBarColor = cBar
        if (!dark) {
            var f = window.decorView.systemUiVisibility
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }

        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)

        val pages = FrameLayout(this)
        pageViews.add(buildAlarmPage())
        pageViews.add(buildMapPage())
        pageViews.add(buildSettingsPage())
        for (v in pageViews) pages.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        rootV.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootV.addView(buildBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(rootV)

        showTab(b?.getInt("tab", 0) ?: 0)

        // карта вантажиться заздалегідь, щоб вона була відкрита одразу після переходу у вкладку
        handler.postDelayed({ if (Net.isOnline(this)) ensureMap() }, 600)

        // перший запуск: просимо всі потрібні дозволи
        if (Store.str(this, "welcomed", "") != "1") {
            Store.setStr(this, "welcomed", "1")
            handler.post { showWelcome() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", cur)
    }

    override fun onResume() {
        super.onResume()
        // доступ дано, але система не підключила сервіс (після оновлення, вбивства процесу): просимо перепідключити
        if (listenerOk() && !AlertListener.connected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, AlertListener::class.java))
            } catch (e: Exception) {
            }
        }
        if (wizardIdx >= 0 && wizardWait) {
            wizardWait = false
            runWizard()
        }
        if (cur == 2) fillPermissions()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        try { web?.destroy() } catch (e: Exception) {}
        web = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            cur == 1 && web?.canGoBack() == true -> web?.goBack()
            cur != 0 -> showTab(0)
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            Store.setStr(this, "tone", uri?.toString() ?: "")
            toneName.text = toneText()
        }
    }

    // ---------- нижня панель вкладок ----------

    private fun buildBar(): View {
        val icons = arrayOf("⏰", "🗺️", "⚙️")
        val names = arrayOf("Будильник", "Мапа", "Налаштування")
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cBar)
        bar.elevation = dpf(10)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        for (i in 0..2) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setPadding(0, dp(6), 0, dp(6))
            val ic = TextView(this)
            ic.text = icons[i]
            ic.textSize = 20f
            ic.gravity = Gravity.CENTER
            box.addView(ic, LinearLayout.LayoutParams(WRAP, WRAP))
            val lb = TextView(this)
            lb.text = names[i]
            lb.textSize = 12f
            lb.gravity = Gravity.CENTER
            lb.setSingleLine(true)
            box.addView(lb, LinearLayout.LayoutParams(WRAP, WRAP))
            box.setOnClickListener { showTab(i) }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            bar.addView(box, lp)
            tabBox.add(box)
            tabLb.add(lb)
        }
        return bar
    }

    private fun showTab(i: Int) {
        cur = i
        for (k in pageViews.indices) pageViews[k].visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in tabBox.indices) {
            val sel = k == i
            tabLb[k].setTextColor(if (sel) cAcc else cSub)
            tabLb[k].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tabBox[k].background = if (sel) shape(a(cAcc, 38), 18) else null
        }
        if (i == 1) ensureMap()
        if (i == 2) fillPermissions()
        refresh()
    }

    // ---------- вкладка «Будильник» ----------

    private fun buildAlarmPage(): View {
        val root = col()

        // попередження, якщо не вистачає важливих дозволів
        banner = col()
        banner.setPadding(dp(18), dp(14), dp(18), dp(14))
        banner.background = shape(a(cBad, 34), 22)
        txt(banner, "⚠️  Не вистачає дозволів", 15f, cText, true)
        txt(banner, "Без них будильник не бачитиме тривоги.", 13f, cSub)
        pill(banner, "Налаштувати", true) { showTab(2) }
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.bottomMargin = dp(14)
        root.addView(banner, blp)

        // головна картка з часом
        val hero = col()
        hero.setPadding(dp(22), dp(20), dp(22), dp(20))
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cAcc, cAcc2))
        g.cornerRadius = dpf(28)
        hero.background = g
        hero.elevation = dpf(5)
        txt(hero, "⏰  Будильник", 14f, a(Color.WHITE, 220))
        timeTv = txt(hero, timeText(), 64f, Color.WHITE, true)
        timeTv.setOnClickListener { showTimeDialog() }
        txt(hero, "Торкніться часу, щоб змінити", 12f, a(Color.WHITE, 190))

        val sw = Switch(this)
        sw.text = "Будильник увімкнено"
        sw.textSize = 16f
        sw.setTextColor(Color.WHITE)
        tintSwitch(sw, Color.WHITE, Color.WHITE, a(Color.WHITE, 150), a(Color.WHITE, 70))
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
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dp(16)
        hero.addView(sw, slp)
        txt(hero, "Задзвонить, коли тривоги немає, а якщо вона йде — одразу після відбою.", 12f, a(Color.WHITE, 200))
            .setPadding(0, dp(8), 0, 0)
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.bottomMargin = dp(14)
        root.addView(hero, hlp)

        // стан тривоги
        val st = card(root)
        txt(st, "Стан зараз", 13f, cSub)
        badge = TextView(this)
        badge.textSize = 20f
        badge.setTypeface(null, Typeface.BOLD)
        badge.setPadding(dp(16), dp(12), dp(16), dp(12))
        val bl = LinearLayout.LayoutParams(MATCH, WRAP)
        bl.topMargin = dp(8)
        st.addView(badge, bl)
        pendingTv = txt(st, "", 14f, cText)
        pendingTv.setPadding(0, dp(12), 0, 0)
        info = txt(st, "", 12f, cSub)
        info.setPadding(0, dp(10), 0, 0)

        // фільтр тривог
        val fc = card(root)
        txt(fc, "Фільтр тривог", 16f, cText, true)
        val rl = txt(fc, "Область (враховувати тривоги лише цього регіону)", 12f, cSub)
        rl.setPadding(0, dp(12), 0, dp(4))
        val spBox = LinearLayout(this)
        spBox.background = shape(cField, 14)
        spBox.setPadding(dp(6), 0, dp(6), 0)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Regions.names)
        sp.setSelection(Store.str(this, "obl", "0").toIntOrNull() ?: 0)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Store.setStr(this@MainActivity, "obl", position.toString())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spBox.addView(sp, LinearLayout.LayoutParams(MATCH, WRAP))
        fc.addView(spBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val fRai = field(fc, "Район або громада (слова через кому, необов’язково)", "rai", "")
        val fApp = field(fc, "Назва застосунку тривог (частина назви)", "app", "тривог")
        val fStart = field(fc, "Слова початку тривоги (через кому)", "start", "повітряна тривога,воздушная тревога")
        val fEnd = field(fc, "Слова відбою (через кому)", "end", "відбій,отбой")
        pill(fc, "Зберегти", true) {
            Store.setStr(this, "rai", fRai.text.toString())
            Store.setStr(this, "app", fApp.text.toString())
            Store.setStr(this, "start", fStart.text.toString())
            Store.setStr(this, "end", fEnd.text.toString())
            Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
            refresh()
        }

        // дії
        val ac = card(root)
        txt(ac, "Дії", 16f, cText, true)
        pill(ac, "🔕  Вимкнути дзвінок", false) {
            startService(Intent(this, RingService::class.java).setAction("stop"))
        }
        pill(ac, "🧪  Тривога зараз: перемкнути вручну", false) {
            val now = !Store.alert(this)
            Store.setAlert(this, now)
            if (!now && Store.pending(this) && Store.enabled(this)) Scheduler.ringSoon(this)
            refresh()
        }

        return scroller(root)
    }

    private fun timeText() = p2(Store.hour(this)) + ":" + p2(Store.minute(this))

    /** Вибір часу колесами (години і хвилини), як у стандартному будильнику Xiaomi. */
    private fun wheel(max: Int, value: Int): NumberPicker {
        val np = NumberPicker(this)
        np.minValue = 0
        np.maxValue = max
        np.wrapSelectorWheel = true
        np.setFormatter { p2(it) }
        np.value = value
        np.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // без цього перше значення показується без ведучого нуля і відкривається клавіатура
        try {
            (np.getChildAt(0) as? EditText)?.filters = arrayOfNulls<InputFilter>(0)
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            np.setTextColor(cText)
            np.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 34f, resources.displayMetrics))
        }
        return np
    }

    private fun showTimeDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = col()
        box.setPadding(dp(24), dp(22), dp(24), dp(18))
        box.background = shape(cCard, 28)
        txt(box, "Час будильника", 14f, cSub)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val hh = wheel(23, Store.hour(this))
        val mm = wheel(59, Store.minute(this))
        row.addView(hh, LinearLayout.LayoutParams(dp(96), dp(170)))
        val colon = TextView(this)
        colon.text = ":"
        colon.textSize = 36f
        colon.setTypeface(null, Typeface.BOLD)
        colon.setTextColor(cAcc)
        colon.setPadding(dp(6), 0, dp(6), dp(4))
        row.addView(colon, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(mm, LinearLayout.LayoutParams(dp(96), dp(170)))
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(10)
        box.addView(row, rlp)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        fun dlgBtn(s: String, filled: Boolean, f: () -> Unit): Button {
            val b = Button(this)
            b.text = s
            b.isAllCaps = false
            b.stateListAnimator = null
            b.setTextColor(if (filled) Color.WHITE else cAcc)
            b.background = if (filled) shape(cAcc, 16) else shape(a(cAcc, 38), 16)
            b.setOnClickListener { f() }
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            btns.addView(b, lp)
            return b
        }
        dlgBtn("Скасувати", false) { d.dismiss() }
        dlgBtn("Готово", true) {
            hh.clearFocus()
            mm.clearFocus()
            Store.setTime(this, hh.value, mm.value)
            if (Store.enabled(this)) Scheduler.scheduleNext(this)
            refresh()
            d.dismiss()
        }
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dp(16)
        box.addView(btns, blp)

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), WRAP)
    }

    private fun refresh() {
        if (!::timeTv.isInitialized) return
        val alert = Store.alert(this)
        timeTv.text = timeText()
        badge.text = if (alert) "🚨  Повітряна тривога" else "✅  Тривоги немає"
        badge.setTextColor(if (alert) cBad else cOk)
        badge.background = shape(a(if (alert) cBad else cOk, 36), 18)
        pendingTv.text = when {
            Store.pending(this) -> "⏳  Будильник чекає на відбій"
            Store.enabled(this) -> "🔔  Будильник увімкнено"
            else -> "💤  Будильник вимкнено"
        }
        info.text = "Читання сповіщень: " + (if (AlertListener.connected) "працює" else "НЕ підключено") +
            "\nІнтернет: " + Net.describe(this) +
            "\nОстаннє сповіщення від застосунку тривог:\n" + Store.str(this, "last", "—")
        banner.visibility = if (notifOk() && listenerOk()) View.GONE else View.VISIBLE
    }

    // ---------- вкладка «Мапа» ----------

    private fun buildMapPage(): View {
        mapHost = FrameLayout(this)
        mapHost.setBackgroundColor(cBg)

        mapBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        mapBar.max = 100
        mapBar.progressTintList = ColorStateList.valueOf(cAcc)
        mapBar.visibility = View.GONE
        mapHost.addView(mapBar, FrameLayout.LayoutParams(MATCH, dp(4)))

        mapErr = col()
        mapErr.gravity = Gravity.CENTER
        mapErr.setBackgroundColor(cBg)
        mapErr.setPadding(dp(32), 0, dp(32), 0)
        val t = txt(mapErr, "🛰️  Не вдалося завантажити мапу.\nПеревірте інтернет.", 16f, cText)
        t.gravity = Gravity.CENTER
        pill(mapErr, "Спробувати ще", true) {
            mapErr.visibility = View.GONE
            if (web == null) ensureMap() else web?.reload()
        }
        mapErr.visibility = View.GONE
        mapHost.addView(mapErr, FrameLayout.LayoutParams(MATCH, MATCH))
        return mapHost
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureMap() {
        if (web != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.setBackgroundColor(cBg)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mapErr.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) mapErr.visibility = View.VISIBLE
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                mapBar.progress = newProgress
                mapBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        mapHost.addView(w, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        web = w
        w.loadUrl(MAP_URL)
    }

    // ---------- вкладка «Налаштування» ----------

    private fun buildSettingsPage(): View {
        val root = col()
        val title = txt(root, "Налаштування", 26f, cText, true)
        title.setPadding(dp(4), 0, 0, dp(14))

        // вигляд
        val look = card(root)
        txt(look, "🎨  Вигляд", 16f, cText, true)
        val ts = Switch(this)
        ts.text = "Темна тема"
        ts.textSize = 15f
        ts.setTextColor(cText)
        tintSwitch(ts, cAcc, cSub, a(cAcc, 110), a(cSub, 70))
        ts.isChecked = dark
        ts.setOnCheckedChangeListener { _, on ->
            Store.setStr(this, "dark", if (on) "1" else "0")
            recreate()
        }
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.topMargin = dp(10)
        look.addView(ts, tlp)

        // мелодія
        val tone = card(root)
        txt(tone, "🎵  Мелодія будильника", 16f, cText, true)
        toneName = txt(tone, toneText(), 14f, cSub)
        toneName.setPadding(0, dp(8), 0, 0)
        pill(tone, "Обрати мелодію", true) {
            val saved = Store.str(this, "tone", "")
            val current = if (saved.isEmpty()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(saved)
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодія будильника")
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            startActivityForResult(i, 10)
        }
        pill(tone, "Стандартна мелодія", false) {
            Store.setStr(this, "tone", "")
            toneName.text = toneText()
        }

        // дозволи
        val perm = card(root)
        txt(perm, "🔐  Дозволи", 16f, cText, true)
        permBox = col()
        perm.addView(permBox, LinearLayout.LayoutParams(MATCH, WRAP))
        pill(perm, "Налаштувати все по черзі", true) { startWizard() }
        pill(perm, "Сторінка застосунку в налаштуваннях", false) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        return scroller(root)
    }

    private fun toneText(): String {
        val s = Store.str(this, "tone", "")
        if (s.isEmpty()) return "Стандартна мелодія будильника"
        val title = try {
            RingtoneManager.getRingtone(this, Uri.parse(s))?.getTitle(this) ?: "обрана"
        } catch (e: Exception) {
            "обрана"
        }
        return title
    }

    // ---------- дозволи ----------

    private fun notifOk(): Boolean =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun listenerOk(): Boolean {
        val l = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return l.contains(packageName)
    }

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun perms(): List<Perm> {
        val list = ArrayList<Perm>()
        list.add(
            Perm(
                "🔔", "Сповіщення",
                "Щоб будильник і стан мережі могли показувати сповіщення.",
                "Дозвольте показ сповіщень",
                { notifOk() },
                {
                    if (Build.VERSION.SDK_INT >= 33 && Store.str(this, "askedNotif", "") != "1") {
                        Store.setStr(this, "askedNotif", "1")
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    }
                }
            )
        )
        list.add(
            Perm(
                "👂", "Доступ до сповіщень",
                "Потрібен, щоб читати сповіщення застосунку тривог. Якщо перемикач сірий: Налаштування → Застосунки → Тривога-будильник → ⋮ → «Дозволити обмежені налаштування».",
                "Увімкніть перемикач для «Тривога-будильник»",
                { listenerOk() },
                { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            )
        )
        list.add(
            Perm(
                "🔋", "Робота у фоні",
                "Без обмежень батареї система не вимикатиме читання сповіщень.",
                "Дозвольте роботу у фоні",
                { batteryOk() },
                {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e2: Exception) {
                        }
                    }
                }
            )
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list.add(
                Perm(
                    "📲", "Повноекранні сповіщення",
                    "Щоб будильник відкривався на заблокованому екрані.",
                    "Увімкніть повноекранні сповіщення",
                    { getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
                    {
                        try {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                            )
                        } catch (e: Exception) {
                        }
                    }
                )
            )
        }
        return list
    }

    private fun fillPermissions() {
        permBox.removeAllViews()
        for (p in perms()) {
            val ok = p.granted()
            val row = col()
            row.setPadding(0, dp(12), 0, dp(4))

            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val tt = TextView(this)
            tt.text = p.icon + "  " + p.title
            tt.textSize = 15f
            tt.setTextColor(cText)
            tt.setTypeface(null, Typeface.BOLD)
            head.addView(tt, LinearLayout.LayoutParams(0, WRAP, 1f))
            val chip = TextView(this)
            chip.text = if (ok) "Дозволено" else "Потрібно"
            chip.textSize = 12f
            chip.setTextColor(if (ok) cOk else cBad)
            chip.background = shape(a(if (ok) cOk else cBad, 36), 12)
            chip.setPadding(dp(10), dp(4), dp(10), dp(4))
            head.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            val d = txt(row, p.desc, 12f, cSub)
            d.setPadding(0, dp(4), 0, 0)
            if (!ok) pill(row, "Надати", false) { p.request() }

            permBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle("Ласкаво просимо!")
            .setMessage(
                "Щоб будильник працював правильно, потрібно дозволити:\n\n" +
                    "🔔  показ сповіщень\n" +
                    "👂  читання сповіщень застосунку тривог\n" +
                    "🔋  роботу у фоні без обмежень батареї\n" +
                    "📲  повноекранні сповіщення (для будильника)\n\n" +
                    "Далі кожен дозвіл буде показано по черзі."
            )
            .setCancelable(false)
            .setPositiveButton("Дозволити") { _, _ -> startWizard() }
            .setNegativeButton("Пізніше", null)
            .show()
    }

    private fun startWizard() {
        wizardIdx = 0
        wizardWait = false
        runWizard()
    }

    private fun runWizard() {
        val list = perms()
        while (wizardIdx in 0 until list.size) {
            val p = list[wizardIdx]
            wizardIdx++
            if (!p.granted()) {
                wizardWait = true
                Toast.makeText(this, p.hint, Toast.LENGTH_LONG).show()
                p.request()
                return
            }
        }
        wizardIdx = -1
        wizardWait = false
        if (cur == 2) fillPermissions()
        refresh()
    }
}
