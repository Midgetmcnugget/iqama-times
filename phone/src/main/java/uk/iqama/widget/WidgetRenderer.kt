package uk.iqama.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the widget as one picture, so any Google Font can be used, in either look:
 *  - Liquid glass: frosted glass panel with a glass tile per prayer (from the TV plugin);
 *  - Timetable strip: a flat panel with the prayers in a row.
 * Both share the strip's formatting: prayer name, adhan time large, iqamah time underneath,
 * with the next prayer's iqamah in a gold box. On taller widgets a banner shows the next
 * prayer; its live countdown is a real ticking text view laid over the picture ([Countdown]).
 */
object WidgetRenderer {
    private val GOLD = Color.rgb(0xE8, 0xC2, 0x6A)
    private val CHIP_TEXT = Color.rgb(0x14, 0x23, 0x2B)
    /** Keep widget pictures well inside Android's memory limit for widgets. */
    private const val MAX_PIXELS = 1_200_000
    /** At or above this height (dp) the banner is shown. */
    const val BANNER_MIN_HEIGHT = 130f

    private val ARABIC = mapOf(
        "FAJR" to "الفجر", "SHURUQ" to "الشروق", "DHUHR" to "الظهر", "ASR" to "العصر",
        "MAGHRIB" to "المغرب", "ISHA" to "العشاء", "JUMUAH1" to "الجمعة"
    )

    /** Where and how to show the ticking countdown on top of the picture (all in dp). */
    data class Countdown(
        val targetMillis: Long, val topDp: Float, val endDp: Float, val sizeDp: Float, val color: Int
    )

    data class Result(val bitmap: Bitmap, val countdown: Countdown?)

    private fun sizeFor(context: Context, wDp: Float, hDp: Float): Triple<Int, Int, Float> {
        var u = context.resources.displayMetrics.density
        val area = wDp * hDp * u * u
        if (area > MAX_PIXELS) u *= sqrt(MAX_PIXELS / area)
        return Triple((wDp * u).toInt().coerceAtLeast(1), (hDp * u).toInt().coerceAtLeast(1), u)
    }

    fun render(
        context: Context, prefs: Prefs, m: Prefs.Mosque, cols: List<IqamaWidget.Column>,
        now: Calendar, wDp: Float, hDp: Float, tomorrow: Boolean = false
    ): Result {
        val (w, h, u) = sizeFor(context, wDp, hDp)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val glass = prefs.style == Prefs.STYLE_GLASS
        val twelve = prefs.clock12h
        val scale = when (prefs.textSize) { 0 -> 0.86f; 2 -> 1.16f; else -> 1f }
        val regular = Fonts.regular(context, prefs.fontName)
        val medium = Fonts.medium(context, prefs.fontName)
        val timeFace = if (prefs.boldTimes) Fonts.bold(context, prefs.fontName) else medium

        // ---- Panel ----
        val panel = if (glass) RectF(2 * u, 1 * u, w - 2 * u, h - 5 * u) else RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = min(22 * u, panel.height() / 2.4f)
        val tint = Appearance.colorFor(context, prefs.background)
        val alpha = prefs.opacity * 255 / 100
        val backdrop = BackgroundImage.cropped(context, w, h)
        if (glass) {
            Glass.drawPanel(c, panel, radius, u, backdrop, tint, alpha)
        } else {
            Glass.drawFlat(c, panel, radius, backdrop, tint, alpha)
        }
        val hasPicture = backdrop != null
        backdrop?.recycle()

        // White text unless the panel is a light, fairly solid colour.
        val pal = Appearance.paletteFor(tint, if (hasPicture) minOf(prefs.opacity, 40) else prefs.opacity)
        val outline = prefs.textOutline
        val outlineColor = if (pal.lightBackground) Color.argb(0x99, 0xFF, 0xFF, 0xFF) else Color.argb(0x80, 0, 0, 0)

        fun draw(s: String, x: Float, y: Float, size: Float, face: Typeface, color: Int,
                 align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = size; typeface = face; textAlign = align; letterSpacing = spacing
                fontFeatureSettings = "tnum"  // digits all the same width, so times line up
            }
            if (outline) {
                p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND
                p.strokeWidth = maxOf(1f * u, size * 0.07f); p.color = outlineColor
                c.drawText(s, x, y, p)
            } else {
                p.setShadowLayer(1.2f * u, 0f, 0.5f * u, Color.argb(0x55, 0, 0, 0))
            }
            p.style = Paint.Style.FILL; p.color = color
            c.drawText(s, x, y, p)
        }

        val pad = 7 * u
        val shown = cols.filter { !it.isSunrise || prefs.showSunrise }
        if (shown.none { it.athan != null }) {
            val msg = if (m.lastError.isNotBlank()) "${m.name}: couldn't load times. Tap to check."
                      else "Loading times for ${m.name}…"
            val p = Paint().apply { textSize = 12 * u; typeface = regular }
            draw(ellipsize(msg, p, panel.width() - 2 * pad), panel.centerX(), panel.centerY() + 4 * u,
                12 * u, regular, pal.muted)
            return Result(bmp, null)
        }

        // Showing tomorrow (after Isha): count the time as a day earlier, so nothing has
        // passed yet, Fajr is next, and its countdown runs to tomorrow.
        val dayShift = if (tomorrow) 1 else 0
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) - dayShift * 24 * 60
        val nextIdx = IqamaWidget.nextIndex(cols, nowMin)
        val nextCol = cols.getOrNull(nextIdx)
        val lang = prefs.nameLanguage

        // ---- Banner (taller widgets): mosque name, next prayer, live countdown ----
        var rowTop = panel.top + pad
        var countdown: Countdown? = null
        if (hDp >= BANNER_MIN_HEIGHT && nextCol != null) {
            val bannerH = min(66 * u, (panel.height() - 2 * pad) * 0.40f)
            val banner = RectF(panel.left + pad, panel.top + pad, panel.right - pad, panel.top + pad + bannerH)
            if (glass) Glass.drawCard(c, banner, min(16 * u, bannerH / 3), u, null)
            else Glass.drawFlatCard(c, banner, min(14 * u, bannerH / 3), pal.lightBackground)

            val inset = 12 * u
            if (prefs.showName) {
                val nameP = Paint().apply { textSize = 12 * u * scale; typeface = regular }
                draw(ellipsize(m.name, nameP, banner.width() - 2 * inset), banner.centerX(),
                    banner.top + 15 * u * scale, 12 * u * scale, regular, pal.muted)
            }
            val nextName = when (lang) {
                "ar" -> ARABIC[nextCol.key] ?: nextCol.label
                "both" -> "${nextCol.label}  ${ARABIC[nextCol.key] ?: ""}".trim()
                else -> nextCol.label
            }
            val big = fit(nextName, min(bannerH * 0.42f, 30 * u) * scale, medium, banner.width() * 0.55f)
            draw(nextName, banner.left + inset, banner.bottom - bannerH * 0.18f, big, medium, pal.text, Paint.Align.LEFT)

            // What we're counting down to: the adhan, then the iqamah; after Isha, tomorrow's Fajr.
            val athan = nextCol.athan
            val iqamah = nextCol.iqamah
            val tomorrow = (nextCol.endTime ?: 0) <= nowMin
            val target: Pair<String, Int>? = when {
                tomorrow && athan != null -> "Adhan in" to athan + 24 * 60
                athan != null && athan > nowMin -> "Adhan in" to athan
                iqamah != null -> "Iqamah in" to iqamah
                else -> null
            }
            if (target != null) {
                val labelSize = 11 * u * scale
                val labelY = banner.bottom - bannerH * 0.18f - bannerH * 0.36f - 2 * u
                draw(target.first, banner.right - inset, labelY, labelSize, regular, pal.muted, Paint.Align.RIGHT)
                val midnight = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, dayShift)
                }
                countdown = Countdown(
                    targetMillis = midnight.timeInMillis + target.second * 60_000L,
                    topDp = (labelY + 2 * u) / u,
                    endDp = (w - (banner.right - inset)) / u,
                    sizeDp = bannerH * 0.36f / u,
                    color = pal.text
                )
            }
            rowTop = banner.bottom + 5 * u
        }

        // ---- Prayer row ----
        val gap = if (glass) 4 * u else 0f
        val bottom = panel.bottom - pad
        val colH = bottom - rowTop
        val colW = (panel.width() - 2 * pad - gap * (shown.size - 1)) / shown.size
        val tileR = min(14 * u, min(colW, colH) / 4)

        val stacked = lang == "both"
        val sample = PrayerTimes.format(23 * 60 + 59, twelve)
        var nameSize = fit("Maghrib", min(colH * 0.17f, 12 * u) * scale, regular, colW * 0.9f)
        if (lang != "en") nameSize = maxOf(nameSize, min(colH * 0.17f, 12 * u) * scale * 0.95f)
        if (stacked) nameSize *= 0.85f
        var timeSize = fit(sample, min(colH * 0.38f, 24 * u) * scale, timeFace, colW * 0.88f)
        var iqSize = fit(sample, min(colH * 0.20f, 13 * u) * scale, regular, colW * 0.70f)
        val nameBlock = if (stacked) nameSize * 2.1f else nameSize
        val stack = nameBlock + 3 * u + timeSize * 0.92f + 4 * u + iqSize * 1.5f
        if (stack > colH * 0.96f) {  // short widget: scale everything down together
            val k = colH * 0.96f / stack
            nameSize *= k; timeSize *= k; iqSize *= k
        }

        shown.forEachIndexed { i, col ->
            val x = panel.left + pad + i * (colW + gap)
            val cell = RectF(x, rowTop, x + colW, bottom)
            val isNext = col === nextCol
            val passed = prefs.fadePassed && !isNext && (col.endTime?.let { it <= nowMin } == true)
            if (glass) Glass.drawCard(c, cell, tileR, u, if (isNext) GOLD else null)

            val cx = cell.centerX()
            val nb = if (stacked) nameSize * 2.1f else nameSize
            val block = nb + 3 * u + timeSize * 0.92f + 4 * u + iqSize * 1.5f
            var y = cell.centerY() - block / 2

            val nameColor = when { isNext -> pal.text; passed -> pal.fadedMuted; else -> pal.muted }
            val en = col.label
            val ar = ARABIC[col.key] ?: en
            when (lang) {
                "ar" -> { y += nameSize; draw(ar, cx, y, nameSize, medium, nameColor) }
                "both" -> {
                    y += nameSize; draw(en, cx, y, nameSize, regular, nameColor)
                    y += nameSize * 1.1f; draw(ar, cx, y, nameSize, regular, nameColor)
                }
                else -> { y += nameSize; draw(en, cx, y, nameSize, regular, nameColor) }
            }

            y += 3 * u + timeSize * 0.92f
            draw(col.athan?.let { PrayerTimes.format(it, twelve) } ?: "--:--", cx, y, timeSize, timeFace,
                if (passed) pal.faded else pal.text)

            // Iqamah underneath; the next prayer's sits in a gold box.
            val iq = if (col.isSunrise) "--" else col.iqamah?.let { PrayerTimes.format(it, twelve) } ?: "--"
            val chipH = iqSize * 1.5f
            val chipTop = y + 4 * u
            if (isNext && col.iqamah != null) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = iqSize; typeface = medium; fontFeatureSettings = "tnum" }
                val tw = tp.measureText(iq)
                val chip = RectF(cx - tw / 2 - 5 * u, chipTop, cx + tw / 2 + 5 * u, chipTop + chipH)
                c.drawRoundRect(chip, 5 * u, 5 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xF2, 0xE8, 0xC2, 0x6A) })
                tp.color = CHIP_TEXT; tp.textAlign = Paint.Align.CENTER
                c.drawText(iq, cx, chipTop + chipH / 2 + iqSize * 0.36f, tp)
            } else {
                draw(iq, cx, chipTop + chipH / 2 + iqSize * 0.36f, iqSize, regular,
                    if (passed) pal.fadedMuted else pal.muted)
            }
        }
        return Result(bmp, countdown)
    }

    /** Largest size up to [max] at which [s] fits in [width]. */
    private fun fit(s: String, max: Float, face: Typeface, width: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = max; fontFeatureSettings = "tnum" }
        val wNow = p.measureText(s)
        return if (wNow <= width || wNow <= 0f) max else max * width / wNow
    }

    private fun ellipsize(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
