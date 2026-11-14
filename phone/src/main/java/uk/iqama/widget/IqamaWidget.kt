package uk.iqama.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Home-screen widget. Both looks (liquid glass and timetable strip) are drawn as one picture by
 * [WidgetRenderer] at the widget's exact size, so any Google Font can be used. On taller widgets
 * a real ticking countdown is laid over the banner. Each widget can show a different mosque;
 * it redraws at every adhan and iqamah and refreshes the times a few times a day.
 */
class IqamaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RefreshWorker.schedule(context)
        maybeAutoRefresh(context)
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        updateAll(context)  // resized: redraw at the new size
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
        RefreshWorker.runNow(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = Prefs(context)
        ids.forEach { prefs.forgetWidget(it) }
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancel(context)
        alarmManager(context).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            maybeAutoRefresh(context)
            updateAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    /** One prayer as shown: [key] is FAJR, SHURUQ, DHUHR, ASR, MAGHRIB, ISHA or JUMUAH1. */
    data class Column(val key: String, val label: String, val athan: Int?, val iqamah: Int?) {
        val isSunrise get() = key == "SHURUQ"
        /** The prayer counts as "done" once its iqamah (or adhan, if no iqamah) has passed. */
        val endTime: Int? get() = iqamah ?: athan
    }

    companion object {
        private const val ACTION_TICK = "uk.iqama.widget.TICK"
        private const val AUTO_REFRESH_GAP_MS = 15 * 60 * 1000L
        private val ORDER = listOf("FAJR", "SHURUQ", "DHUHR", "ASR", "MAGHRIB", "ISHA")

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, IqamaWidget::class.java))

        /** Every mosque currently on a widget (or the default if there are none). */
        fun mosquesInUse(context: Context): Set<String> {
            val prefs = Prefs(context)
            val used = widgetIds(context).map { prefs.mosqueFor(it) }.toSet()
            return used.ifEmpty { setOf(prefs.defaultMosque) }
        }

        /** Starts a refresh if any mosque's times are out of date, at most every 15 minutes. */
        private fun maybeAutoRefresh(context: Context) {
            val prefs = Prefs(context)
            if (mosquesInUse(context).none { Fetcher.isStale(prefs.mosque(it)) }) return
            val sp = context.getSharedPreferences("iqama_widget_state", Context.MODE_PRIVATE)
            val last = sp.getLong("auto_refresh_at", 0L)
            if (System.currentTimeMillis() - last < AUTO_REFRESH_GAP_MS) return
            sp.edit().putLong("auto_refresh_at", System.currentTimeMillis()).apply()
            RefreshWorker.runNow(context)
        }

        /** Redraws every widget and schedules the next redraw. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val prefs = Prefs(context)
            val now = Calendar.getInstance()
            val events = sortedSetOf<Int>()
            for (id in ids) {
                val m = prefs.mosque(prefs.mosqueFor(id))
                val cols = columns(m, now)
                cols.forEach { c -> c.athan?.let { events.add(it) }; c.iqamah?.let { events.add(it) } }
                try {
                    manager.updateAppWidget(id, buildViews(context, prefs, m, cols, now, id, manager))
                } catch (e: Throwable) {
                    android.util.Log.e("IqamaWidget", "Couldn't draw widget $id", e)
                }
            }
            scheduleNextTick(context, now, events)
        }

        /** Today's columns: Fajr, Shuruq, Dhuhr (Jumu'ah on Fridays), Asr, Maghrib, Isha. */
        fun columns(m: Prefs.Mosque, now: Calendar): List<Column> {
            val byKey = PrayerTimes.fromJson(m.timesJson).associateBy { it.key }
            val friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            return ORDER.map { key ->
                val jumuah = byKey["JUMUAH1"]
                when {
                    key == "DHUHR" && friday && jumuah != null ->
                        Column("JUMUAH1", "Jumu'ah", jumuah.athan, jumuah.iqamah)
                    key == "SHURUQ" -> Column(key, "Shuruq", byKey[key]?.athan, null)
                    else -> {
                        val p = byKey[key]
                        val estimated = if (p?.athan != null && m.iqamahOffset >= 0)
                            p.athan + if (key == "MAGHRIB") m.maghribOffset else m.iqamahOffset
                        else null
                        Column(key, PrayerTimes.label(key), p?.athan, p?.iqamah ?: estimated)
                    }
                }
            }
        }

        /** Index of the prayer to highlight: the first whose iqamah hasn't passed; after Isha, Fajr. */
        fun nextIndex(cols: List<Column>, nowMin: Int): Int {
            val i = cols.indexOfFirst { !it.isSunrise && (it.endTime?.let { t -> t > nowMin } == true) }
            return if (i >= 0) i else cols.indexOfFirst { !it.isSunrise && it.endTime != null }
        }

        /** Tapping a widget opens its settings (mosque etc.). */
        private fun openSettings(context: Context, widgetId: Int): PendingIntent {
            val open = Intent(context, SettingsActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("iqama://widget/$widgetId"))  // keeps each widget's intent distinct
            return PendingIntent.getActivity(context, widgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** The widget's size in dp as it is on screen (portrait), with a sensible default. */
        private fun sizeDp(manager: AppWidgetManager, id: Int): Pair<Float, Float> {
            val opts = manager.getAppWidgetOptions(id)
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = opts.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                val tallest = sizes?.maxByOrNull { it.height }
                if (tallest != null && tallest.width > 0 && tallest.height > 0) return tallest.width to tallest.height
            }
            val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            return (if (w > 0) w.toFloat() else 320f) to (if (h > 0) h.toFloat() else 90f)
        }

        private fun buildViews(
            context: Context, prefs: Prefs, m: Prefs.Mosque, cols: List<Column>,
            now: Calendar, widgetId: Int, manager: AppWidgetManager
        ): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_picture)
            v.setOnClickPendingIntent(R.id.root, openSettings(context, widgetId))
            val (wDp, hDp) = sizeDp(manager, widgetId)
            val result = WidgetRenderer.render(context, prefs, m, cols, now, wDp, hDp)
            v.setImageViewBitmap(R.id.picture, result.bitmap)

            val cd = result.countdown
            if (cd == null) {
                v.setViewVisibility(R.id.countdown, View.GONE)
            } else {
                // Place the ticking countdown over the banner, right-aligned under its label.
                val d = context.resources.displayMetrics.density
                v.setViewPadding(R.id.countdown_box, 0, (cd.topDp * d).toInt(), (cd.endDp * d).toInt(), 0)
                v.setTextViewTextSize(R.id.countdown, TypedValue.COMPLEX_UNIT_DIP, cd.sizeDp)
                v.setTextColor(R.id.countdown, cd.color)
                v.setChronometer(R.id.countdown,
                    SystemClock.elapsedRealtime() + (cd.targetMillis - System.currentTimeMillis()), null, true)
                v.setChronometerCountDown(R.id.countdown, true)
                v.setViewVisibility(R.id.countdown, View.VISIBLE)
            }
            return v
        }

        private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, IqamaWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** True if Android lets the widget switch over exactly on time. */
        fun canBeExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms()

        /**
         * Wakes the widget at the next adhan or iqamah time (or just after midnight) so the
         * highlight and countdown move on. Exact if allowed in settings, otherwise Android may
         * run it a little late.
         */
        private fun scheduleNextTick(context: Context, now: Calendar, events: Set<Int>) {
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val nextMin = events.filter { it > nowMin }.minOrNull()
            val at = Calendar.getInstance().apply {
                set(Calendar.SECOND, 1)
                set(Calendar.MILLISECOND, 0)
                if (nextMin != null) {
                    set(Calendar.HOUR_OF_DAY, nextMin / 60)
                    set(Calendar.MINUTE, nextMin % 60)
                } else {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 1)
                }
            }
            val am = alarmManager(context)
            val pi = tickIntent(context)
            try {
                if (canBeExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC, at.timeInMillis, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC, at.timeInMillis, pi)
            } catch (_: SecurityException) {
                am.set(AlarmManager.RTC, at.timeInMillis, pi)
            }
        }
    }
}

/** Redraws the widgets after the clock, date or time zone changes. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IqamaWidget.updateAll(context)
    }
}
