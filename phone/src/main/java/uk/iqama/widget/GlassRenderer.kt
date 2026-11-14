package uk.iqama.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the "glass" widget as one picture, laid out like the TV plugin: an optional mosque-name
 * header, then a glass tile per prayer with its name, the iqamah time large and "Adhan hh:mm"
 * underneath. The next prayer's tile is gold; prayers that have passed fade.
 * Drawing it ourselves (rather than with standard widget text) is what allows the glass effect
 * and any Google Font.
 */
object GlassRenderer {
    private val GOLD = Color.rgb(0xE8, 0xC2, 0x6A)
    /** Keep widget pictures well inside Android's memory limit for widgets. */
    private const val MAX_PIXELS = 1_200_000

    /** Pixel size and scale (pixels per dp) for a widget of [wDp]×[hDp]. */
    fun sizeFor(context: Context, wDp: Float, hDp: Float): Triple<Int, Int, Float> {
        var u = context.resources.displayMetrics.density
        val area = wDp * hDp * u * u
        if (area > MAX_PIXELS) u *= sqrt(MAX_PIXELS / area)
        return Triple((wDp * u).toInt().coerceAtLeast(1), (hDp * u).toInt().coerceAtLeast(1), u)
    }

    fun render(
        context: Context, prefs: Prefs, m: Prefs.Mosque, cols: List<IqamaWidget.Column>,
        now: Calendar, wDp: Float, hDp: Float
    ): Bitmap {
        val (w, h, u) = sizeFor(context, wDp, hDp)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val twelve = prefs.clock12h
        val regular = Fonts.regular(context, prefs.fontName)
        val medium = Fonts.medium(context, prefs.fontName)

        // Glass panel: fills the widget, leaving room for its soft shadow.
        val panel = RectF(2 * u, 1 * u, w - 2 * u, h - 5 * u)
        val radius = min(22 * u, panel.height() / 2.4f)
        val tint = Appearance.colorFor(context, prefs.background)
        val backdrop = BackgroundImage.cropped(context, w, h)
        Glass.drawPanel(c, panel, radius, u, backdrop, tint, prefs.opacity * 255 / 100)
        backdrop?.recycle()

        // White text on glass, unless the glass is a light colour and fairly solid.
        val pal = Appearance.paletteFor(tint, if (backdrop != null) minOf(prefs.opacity, 40) else prefs.opacity)

        val pad = 7 * u
        val showHeader = prefs.showName && hDp >= 100f
        val headerH = if (showHeader) 24 * u else 0f
        if (showHeader) {
            val p = text(pal.text, 14 * u, medium, u)
            c.drawText(ellipsize(m.name, p, panel.width() - 2 * pad), panel.left + pad + 2 * u,
                panel.top + pad + 14 * u, p)
            val stale = m.fetchedDay.isNotBlank() && m.fetchedDay != Fetcher.today()
            if (stale) {
                c.drawText("times from ${m.fetchedDay}", panel.right - pad - 2 * u, panel.top + pad + 14 * u,
                    text(pal.muted, 10 * u, regular, u, Paint.Align.RIGHT))
            }
        }

        val shown = cols.filter { !it.isSunrise || prefs.showSunrise }
        if (shown.none { it.athan != null }) {
            val msg = if (m.lastError.isNotBlank()) "${m.name}: couldn't load times. Tap to check."
                      else "Loading times for ${m.name}…"
            val p = text(pal.muted, 12 * u, regular, u, Paint.Align.CENTER)
            c.drawText(ellipsize(msg, p, panel.width() - 2 * pad), panel.centerX(),
                panel.centerY() + headerH / 2 + 4 * u, p)
            return bmp
        }

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val nextCol = cols.getOrNull(IqamaWidget.nextIndex(cols, nowMin))

        // Tiles
        val gap = 4 * u
        val top = panel.top + pad + headerH
        val bottom = panel.bottom - pad
        val tileH = bottom - top
        val tileW = (panel.width() - 2 * pad - gap * (shown.size - 1)) / shown.size
        val tileR = min(14 * u, min(tileW, tileH) / 4)

        // Text sizes from the tile size, shrunk until the widest text fits.
        val sample = PrayerTimes.format(23 * 60 + 59, twelve)
        var big = min(tileH * 0.40f, 28 * u)
        big = fit(sample, big, medium, tileW * 0.86f)
        var label = fit("MAGHRIB", min(tileH * 0.17f, 11 * u), medium, tileW * 0.86f, letterSpacing = 0.08f)
        var small = fit("Adhan $sample", min(tileH * 0.15f, 10 * u), regular, tileW * 0.90f)
        val stack = label + big * 0.92f + small + 6 * u
        if (stack > tileH * 0.94f) {  // very short widget: scale everything down together
            val k = tileH * 0.94f / stack
            big *= k; label *= k; small *= k
        }

        shown.forEachIndexed { i, col ->
            val x = panel.left + pad + i * (tileW + gap)
            val tile = RectF(x, top, x + tileW, bottom)
            val isNext = col === nextCol
            val passed = !isNext && (col.endTime?.let { it <= nowMin } == true)
            Glass.drawCard(c, tile, tileR, u, if (isNext) GOLD else null)

            val cx = tile.centerX()
            val block = label + 3 * u + big * 0.92f + 3 * u + small
            var y = tile.centerY() - block / 2

            y += label
            val labelColor = when { isNext -> GOLD; passed -> pal.fadedMuted; else -> pal.muted }
            c.drawText(col.label.uppercase(Locale.UK), cx, y,
                text(labelColor, label, medium, u, Paint.Align.CENTER).apply { letterSpacing = 0.08f })

            y += 3 * u + big * 0.92f
            val bigTime = col.iqamah ?: col.athan
            c.drawText(bigTime?.let { PrayerTimes.format(it, twelve) } ?: "--:--", cx, y,
                text(if (passed) pal.faded else pal.text, big, regular, u, Paint.Align.CENTER))

            y += 3 * u + small
            val sub = when {
                col.isSunrise -> "Sunrise"
                col.iqamah != null && col.athan != null -> "Adhan " + PrayerTimes.format(col.athan, twelve)
                col.athan != null -> "Adhan"
                else -> ""
            }
            c.drawText(sub, cx, y, text(if (passed) pal.fadedMuted else pal.muted, small, regular, u, Paint.Align.CENTER))
        }
        return bmp
    }

    private fun text(color: Int, size: Float, face: Typeface, u: Float, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; textSize = size; typeface = face; textAlign = align
            setShadowLayer(1.2f * u, 0f, 0.5f * u, Color.argb(0x55, 0, 0, 0))
        }

    /** Largest size up to [max] at which [s] fits in [width]. */
    private fun fit(s: String, max: Float, face: Typeface, width: Float, letterSpacing: Float = 0f): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = max; this.letterSpacing = letterSpacing }
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
