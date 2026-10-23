package ua.trevogaalarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

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
