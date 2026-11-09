package uk.iqama.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Home-screen widget: five tiles (Fajr to Isha) with the iqamah time large and the adhan below.
 * The next prayer is highlighted in gold and earlier ones fade. The widget redraws itself when
 * each prayer passes, and refreshes the times in the background a few times a day.
 */
class IqamaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RefreshWorker.schedule(context)
        maybeAutoRefresh(context)
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        updateAll(context)  // resized: show or hide the name row to fit
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
        RefreshWorker.runNow(context)
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

    companion object {
        private const val ACTION_TICK = "uk.iqama.widget.TICK"
        private const val AUTO_REFRESH_GAP_MS = 15 * 60 * 1000L

        private val GOLD = Color.rgb(0xE8, 0xC2, 0x6A)
        private val WHITE = Color.WHITE
        private val MUTED = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        private val FADED = Color.argb(0x80, 0xFF, 0xFF, 0xFF)
        private val FADED_MUTED = Color.argb(0x5A, 0xFF, 0xFF, 0xFF)

        private val CELLS = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4)
        private val LABELS = intArrayOf(R.id.label0, R.id.label1, R.id.label2, R.id.label3, R.id.label4)
        private val TIMES = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4)
        private val SUBS = intArrayOf(R.id.sub0, R.id.sub1, R.id.sub2, R.id.sub3, R.id.sub4)

        /** Starts a refresh if the times are out of date, at most every 15 minutes. */
        private fun maybeAutoRefresh(context: Context) {
            val prefs = Prefs(context)
            if (!Fetcher.isStale(prefs)) return
            val sp = context.getSharedPreferences("iqama_widget_state", Context.MODE_PRIVATE)
            val last = sp.getLong("auto_refresh_at", 0L)
            if (System.currentTimeMillis() - last < AUTO_REFRESH_GAP_MS) return
            sp.edit().putLong("auto_refresh_at", System.currentTimeMillis()).apply()
            RefreshWorker.runNow(context)
        }

        /** Redraws every Iqama widget on the home screen and schedules the next redraw. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, IqamaWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = Prefs(context)
            val now = Calendar.getInstance()
            for (id in ids) {
                val minHeight = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                manager.updateAppWidget(id, build(context, prefs, now, minHeight))
            }
            scheduleNextTick(context, prefs, now)
        }

        data class Column(val label: String, val athan: Int?, val iqamah: Int?) {
            val sortTime: Int? get() = iqamah ?: athan
        }

        /** Today's five columns. Dhuhr becomes Jumu'ah on Fridays; offsets fill in missing iqamah. */
        fun columns(prefs: Prefs, now: Calendar): List<Column> {
            val byKey = PrayerTimes.fromJson(prefs.timesJson).associateBy { it.key }
            val friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            return PrayerTimes.DAILY.map { key ->
                val jumuah = byKey["JUMUAH1"]
                if (key == "DHUHR" && friday && jumuah != null) {
                    Column("Jumu'ah", jumuah.athan, jumuah.iqamah)
                } else {
                    val p = byKey[key]
                    val estimated = if (p?.athan != null && prefs.iqamahOffset >= 0)
                        p.athan + if (key == "MAGHRIB") prefs.maghribOffset else prefs.iqamahOffset
                    else null
                    Column(PrayerTimes.label(key), p?.athan, p?.iqamah ?: estimated)
                }
            }
        }

        private fun build(context: Context, prefs: Prefs, now: Calendar, minHeightDp: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_iqama)
            val twelve = prefs.clock12h

            // Tapping the widget opens its settings.
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, SettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.root, open)

            // The name row only shows if switched on and the widget is tall enough for it.
            val showName = prefs.showName && minHeightDp >= 100
            v.setViewVisibility(R.id.header, if (showName) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.mosque_name, prefs.mosqueTitle)
            val stale = prefs.fetchedDay.isNotBlank() && prefs.fetchedDay != Fetcher.today()
            v.setTextViewText(R.id.status, if (stale) "times from " + prettyDay(prefs.fetchedDay) else "")

            val cols = columns(prefs, now)
            val haveTimes = cols.any { it.sortTime != null }
            v.setViewVisibility(R.id.row, if (haveTimes) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.message, if (haveTimes) View.GONE else View.VISIBLE)
            if (!haveTimes) {
                v.setTextViewText(
                    R.id.message,
                    if (prefs.lastError.isNotBlank()) "Couldn't load times: ${prefs.lastError}. Tap to check settings."
                    else "Loading iqamah times…"
                )
                return v
            }

            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val nextIdx = cols.indexOfFirst { c -> c.sortTime?.let { it > nowMin } == true }
                .let { if (it < 0 && cols.first().sortTime != null) 0 else it }  // after Isha: Fajr

            cols.forEachIndexed { i, col ->
                val isNext = i == nextIdx
                val passed = !isNext && col.sortTime?.let { it <= nowMin } == true
                v.setInt(CELLS[i], "setBackgroundResource", if (isNext) R.drawable.cell_next_bg else R.drawable.cell_bg)
                v.setTextViewText(LABELS[i], col.label.uppercase(Locale.UK))
                v.setTextColor(LABELS[i], when { isNext -> GOLD; passed -> FADED_MUTED; else -> MUTED })
                val big = col.iqamah ?: col.athan
                v.setTextViewText(TIMES[i], big?.let { PrayerTimes.format(it, twelve) } ?: "--:--")
                v.setTextColor(TIMES[i], if (passed) FADED else WHITE)
                val sub = when {
                    col.iqamah != null && col.athan != null -> "Adhan " + PrayerTimes.format(col.athan, twelve)
                    col.athan != null -> "Adhan"
                    else -> ""
                }
                v.setTextViewText(SUBS[i], sub)
                v.setTextColor(SUBS[i], if (passed) FADED_MUTED else MUTED)
                // On a one-row-high widget there's only room for the name and iqamah time.
                v.setViewVisibility(SUBS[i], if (minHeightDp < 75) View.GONE else View.VISIBLE)
            }
            return v
        }

        private fun prettyDay(day: String): String = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(day)
            SimpleDateFormat("d MMM", Locale.UK).format(d!!)
        } catch (_: Exception) { day }

        private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, IqamaWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Wakes the widget just after the next prayer time (or at midnight), so the gold highlight
         * moves on. Inexact, so it needs no special permission and may run a minute or two late.
         */
        private fun scheduleNextTick(context: Context, prefs: Prefs, now: Calendar) {
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val nextMin = columns(prefs, now).mapNotNull { it.sortTime }.filter { it >= nowMin }.minOrNull()
            val at = Calendar.getInstance().apply {
                set(Calendar.SECOND, 5)
                set(Calendar.MILLISECOND, 0)
                if (nextMin != null) {
                    set(Calendar.HOUR_OF_DAY, nextMin / 60)
                    set(Calendar.MINUTE, nextMin % 60 + 1)
                } else {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 1)
                }
            }
            alarmManager(context).set(AlarmManager.RTC, at.timeInMillis, tickIntent(context))
        }
    }
}

/** Redraws the widget after the clock, date or time zone changes. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IqamaWidget.updateAll(context)
    }
}
