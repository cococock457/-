package ua.trevogaalarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }

    companion object {

        /** Викликати після будь-якої зміни стану тривоги (Alerts.set вже це робить). */
        fun updateAll(context: Context) {
            val ctx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AlarmWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun build(context: Context): RemoteViews {
            val alert = try { Store.alert(context) } catch (e: Exception) { false }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            val v = RemoteViews(context.packageName, R.layout.widget_alarm)

            if (alert) {
                v.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_alert)
                v.setTextViewText(R.id.widget_icon, "\uD83D\uDEA8")
                v.setTextViewText(R.id.widget_title, "Тривога!")
                v.setTextViewText(R.id.widget_sub, "Будильник чекає відбою · $time")
            } else {
                v.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_ok)
                v.setTextViewText(R.id.widget_icon, "\u2705")
                v.setTextViewText(R.id.widget_title, "Тривоги немає")
                v.setTextViewText(R.id.widget_sub, "Оновлено $time")
            }

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }
    }
}
