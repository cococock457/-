package ua.trevogaalarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Calendar

/** Віджет головного екрана: стан тривоги і найближчий будильник. */
class AlarmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val rv = build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, rv)
    }

    companion object {
        fun updateAll(c: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(c)
                val ids = mgr.getAppWidgetIds(ComponentName(c, AlarmWidget::class.java))
                if (ids.isEmpty()) return
                val rv = build(c)
                for (id in ids) mgr.updateAppWidget(id, rv)
            } catch (e: Exception) {
            }
        }

        private fun p2(n: Int) = if (n < 10) "0$n" else n.toString()

        private fun build(c: Context): RemoteViews {
            val alert = Store.alert(c)
            val rv = RemoteViews(c.packageName, R.layout.widget_alarm)
            rv.setTextViewText(R.id.w_icon, if (alert) "🚨" else "✅")
            rv.setTextViewText(R.id.w_title, if (alert) "Повітряна тривога" else "Тривоги немає")

            val t = Scheduler.nextAt(c)
            val sub = if (t == null) {
                "Будильник вимкнено"
            } else {
                val cal = Calendar.getInstance()
                cal.timeInMillis = t
                "⏰ " + p2(cal.get(Calendar.HOUR_OF_DAY)) + ":" + p2(cal.get(Calendar.MINUTE)) +
                    (if (Store.pending(c)) " · чекає відбою" else "")
            }
            rv.setTextViewText(R.id.w_sub, sub)
            rv.setInt(
                R.id.w_root, "setBackgroundResource",
                if (alert) R.drawable.widget_bg_alert else R.drawable.widget_bg_ok
            )

            val open = PendingIntent.getActivity(
                c, 7, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)
            return rv
        }
    }
}
