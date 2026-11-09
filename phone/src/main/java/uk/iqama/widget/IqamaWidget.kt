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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Home-screen widget in the style of a mosque timetable strip:
 *  - one row high (e.g. 5x1): each prayer's name, adhan time (bold) and iqamah time underneath,
 *    with the next prayer's iqamah in a gold box;
 *  - two rows or taller: a banner on top with the mosque, the next prayer and a live countdown
 *    to its adhan or iqamah.
 * Each widget can show a different mosque. It redraws at every adhan and iqamah time and
 * refreshes the times in the background a few times a day.
 */
class IqamaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RefreshWorker.schedule(context)
        maybeAutoRefresh(context)
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        updateAll(context)  // resized: switch between the one-row and banner layouts
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
        /** At or above this height (dp) the banner layout is used. */
        private const val FULL_LAYOUT_MIN_HEIGHT = 130

        private val WHITE = Color.WHITE
        private val MUTED = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        private val FADED = Color.argb(0x80, 0xFF, 0xFF, 0xFF)
        private val FADED_MUTED = Color.argb(0x5A, 0xFF, 0xFF, 0xFF)
        private val CHIP_TEXT = Color.rgb(0x14, 0x23, 0x2B)

        private val ORDER = listOf("FAJR", "SHURUQ", "DHUHR", "ASR", "MAGHRIB", "ISHA")
        private val CELLS = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4, R.id.cell5)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5)
        private val ADHANS = intArrayOf(R.id.adhan0, R.id.adhan1, R.id.adhan2, R.id.adhan3, R.id.adhan4, R.id.adhan5)
        private val IQAMAHS = intArrayOf(R.id.iqamah0, R.id.iqamah1, R.id.iqamah2, R.id.iqamah3, R.id.iqamah4, R.id.iqamah5)

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
                val compact = build(context, prefs, m, cols, now, id, full = false)
                val full = build(context, prefs, m, cols, now, id, full = true)
                if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks the layout that fits the widget's current size.
                    manager.updateAppWidget(id, RemoteViews(mapOf(
                        SizeF(150f, 40f) to compact,
                        SizeF(150f, FULL_LAYOUT_MIN_HEIGHT.toFloat()) to full
                    )))
                } else {
                    val opts = manager.getAppWidgetOptions(id)
                    val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                    manager.updateAppWidget(id, if (h >= FULL_LAYOUT_MIN_HEIGHT) full else compact)
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

        private fun build(
            context: Context, prefs: Prefs, m: Prefs.Mosque, cols: List<Column>,
            now: Calendar, widgetId: Int, full: Boolean
        ): RemoteViews {
            val v = RemoteViews(context.packageName, if (full) R.layout.widget_full else R.layout.widget_compact)
            val twelve = prefs.clock12h

            // Tapping opens this widget's settings (mosque etc.).
            val open = Intent(context, SettingsActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("iqama://widget/$widgetId"))  // keeps each widget's intent distinct
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(context, widgetId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            val haveTimes = cols.any { it.athan != null }
            v.setViewVisibility(R.id.row, if (haveTimes) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.message, if (haveTimes) View.GONE else View.VISIBLE)
            if (full) {
                v.setTextViewText(R.id.mosque_name, m.name)
                v.setViewVisibility(R.id.mosque_name, if (prefs.showName) View.VISIBLE else View.GONE)
            }
            if (!haveTimes) {
                v.setTextViewText(
                    R.id.message,
                    if (m.lastError.isNotBlank()) "${m.name}: couldn't load times (${m.lastError}). Tap to check."
                    else "Loading times for ${m.name}…"
                )
                if (full) {
                    v.setTextViewText(R.id.next_name, "")
                    v.setTextViewText(R.id.countdown_label, "")
                    v.setViewVisibility(R.id.countdown, View.GONE)
                }
                return v
            }

            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val next = nextIndex(cols, nowMin)

            cols.forEachIndexed { i, col ->
                if (col.isSunrise && !prefs.showSunrise) {
                    v.setViewVisibility(CELLS[i], View.GONE); return@forEachIndexed
                }
                v.setViewVisibility(CELLS[i], View.VISIBLE)
                val isNext = i == next
                val passed = !isNext && (col.endTime?.let { it <= nowMin } == true)

                v.setTextViewText(NAMES[i], col.label)
                v.setTextColor(NAMES[i], when { isNext -> WHITE; passed -> FADED_MUTED; else -> MUTED })

                v.setTextViewText(ADHANS[i], col.athan?.let { PrayerTimes.format(it, twelve) } ?: "--:--")
                v.setTextColor(ADHANS[i], if (passed) FADED else WHITE)

                v.setTextViewText(IQAMAHS[i], col.iqamah?.let { PrayerTimes.format(it, twelve) } ?: "--")
                v.setInt(IQAMAHS[i], "setBackgroundResource", if (isNext) R.drawable.iqamah_chip_next else 0)
                v.setTextColor(IQAMAHS[i], when { isNext -> CHIP_TEXT; passed -> FADED_MUTED; else -> MUTED })
            }

            if (full) bindBanner(v, cols, next, nowMin)
            return v
        }

        /** "Isha · Time until iqamah 47:16", counting down live (no redraw needed each second). */
        private fun bindBanner(v: RemoteViews, cols: List<Column>, next: Int, nowMin: Int) {
            val col = cols.getOrNull(next) ?: return
            v.setTextViewText(R.id.next_name, col.label)
            val athan = col.athan
            val iqamah = col.iqamah
            // Before the adhan: count to the adhan. Between adhan and iqamah: count to the iqamah.
            // After Isha the next is tomorrow's Fajr.
            val tomorrow = (col.endTime ?: 0) <= nowMin
            val (label, targetMin) = when {
                tomorrow && athan != null -> "Time until adhan" to athan + 24 * 60
                athan != null && athan > nowMin -> "Time until adhan" to athan
                iqamah != null -> "Time until iqamah" to iqamah
                else -> "" to null
            }
            v.setTextViewText(R.id.countdown_label, label)
            if (targetMin == null) {
                v.setViewVisibility(R.id.countdown, View.GONE); return
            }
            val cal = Calendar.getInstance()
            val nowMs = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val targetMs = cal.timeInMillis + targetMin * 60_000L
            v.setViewVisibility(R.id.countdown, View.VISIBLE)
            v.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + (targetMs - nowMs), null, true)
            v.setChronometerCountDown(R.id.countdown, true)
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
