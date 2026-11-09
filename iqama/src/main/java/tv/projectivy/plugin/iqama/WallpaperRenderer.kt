package tv.projectivy.plugin.iqama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Draws the 1920x1080 wallpaper: background plus a panel with today's five iqamah times. */
object WallpaperRenderer {
    const val W = 1920
    const val H = 1080

    private val GOLD = Color.rgb(0xE8, 0xC2, 0x6A)
    private val WHITE = Color.WHITE
    private val MUTED = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)

    private const val PANEL_RADIUS = 44f
    private const val CARD_RADIUS = 28f

    private fun paint(color: Int, size: Float, face: Typeface, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = face; textAlign = align
            setShadowLayer(6f, 0f, 2f, Color.argb(0x80, 0, 0, 0))
        }

    data class Column(val key: String, val label: String, val athan: Int?, val iqamah: Int?) {
        val sortTime: Int? get() = iqamah ?: athan
    }

    /** The five columns for today: Dhuhr becomes Jumu'ah on Fridays when the mosque lists it. */
    fun columns(prayers: List<Prayer>, now: Calendar): List<Column> {
        val byKey = prayers.associateBy { it.key }
        val friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        return PrayerTimes.DAILY.map { key ->
            val jumuah = byKey["JUMUAH1"]
            if (key == "DHUHR" && friday && jumuah != null) {
                Column("JUMUAH1", "Jumu'ah", jumuah.athan, jumuah.iqamah)
            } else {
                val p = byKey[key]
                Column(key, PrayerTimes.label(key), p?.athan, p?.iqamah ?: estimatedIqamah(key, p?.athan))
            }
        }
    }

    /** Iqamah from the "iqamah after adhan" setting, for pages that only list adhan times. */
    private fun estimatedIqamah(key: String, athan: Int?): Int? {
        if (athan == null || Prefs.iqamahOffset < 0) return null
        return athan + if (key == "MAGHRIB") Prefs.maghribOffset else Prefs.iqamahOffset
    }

    fun render(context: Context, now: Calendar): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBackground(context, c)

        val prayers = PrayerTimes.fromJson(Prefs.timesJson)
        val twelve = Prefs.clock12h
        // Chosen font (Google Fonts), or the TV's Roboto if none is set or it can't be loaded.
        val regular = Fonts.regular(context)
        val medium = Fonts.medium(context)
        val showName = Prefs.showMosqueName

        // Panel geometry
        val panelW = 1680f
        val panelH = Prefs.panelHeight.toFloat()
        val left = (W - panelW) / 2f
        val top = Prefs.panelTop.toFloat()
        val panel = RectF(left, top, left + panelW, top + panelH)

        Glass.drawPanel(bmp, c, panel, PANEL_RADIUS)

        val pad = 40f
        val inner = Prefs.PANEL_PADDING.toFloat()
        // Optional header with the mosque name; without it the panel is shorter and the tiles move up.
        val headerY = top + pad + 34f
        val title = mosqueTitle()
        if (showName) c.drawText(title, left + pad, headerY, paint(WHITE, 38f, medium))

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (prayers.isEmpty()) {
            val msg = if (Prefs.lastError.isNotBlank()) "Couldn't load times: ${Prefs.lastError}"
                      else "Loading iqamah times from Masjidbox…"
            val msgY = if (showName) panel.centerY() + 40f else panel.centerY() + 14f
            c.drawText(msg, panel.centerX(), msgY, paint(MUTED, 40f, regular, Paint.Align.CENTER))
            return bmp
        }

        val cols = columns(prayers, now)
        // The next prayer is highlighted. After Isha, highlight tomorrow's Fajr instead.
        val nextIdx = cols.indexOfFirst { col -> col.sortTime?.let { it > nowMin } == true }
            .let { if (it < 0 && cols.firstOrNull()?.sortTime != null) 0 else it }

        // Note if the times are from an earlier day (e.g. TV offline)
        if (Prefs.fetchedDay.isNotBlank() && Prefs.fetchedDay != MasjidboxFetcher.today()) {
            val note = "times from " + prettyDay(Prefs.fetchedDay)
            if (showName) {
                val titleW = paint(WHITE, 38f, medium).measureText(title)
                c.drawText(note, left + pad + titleW + 24f, headerY, paint(MUTED, 26f, regular))
            } else {
                // No header: put the note just under the panel instead.
                val below = if (panel.bottom + 44f < H) panel.bottom + 40f else panel.top - 14f
                c.drawText(note, panel.right - pad, below, paint(MUTED, 26f, regular, Paint.Align.RIGHT))
            }
        }

        // Cards
        val gap = 22f
        val cardTop = top + inner + if (showName) Prefs.HEADER_HEIGHT.toFloat() else 0f
        val cardH = Prefs.CARD_HEIGHT.toFloat()
        val cardW = (panelW - 2 * pad - gap * (cols.size - 1)) / cols.size
        cols.forEachIndexed { i, col ->
            val x = left + pad + i * (cardW + gap)
            val card = RectF(x, cardTop, x + cardW, cardTop + cardH)
            val isNext = i == nextIdx
            val passed = col.sortTime?.let { it <= nowMin } == true && !isNext
            Glass.drawCard(c, card, CARD_RADIUS, if (isNext) GOLD else null)
            val fade = if (passed) 0x80 else 0xFF
            val cx = card.centerX()
            c.drawText(col.label.uppercase(Locale.UK), cx, card.top + 50f,
                paint(withAlpha(if (isNext) GOLD else MUTED, fade), 28f, medium, Paint.Align.CENTER).apply { letterSpacing = 0.12f })
            val big = col.iqamah ?: col.athan
            c.drawText(big?.let { PrayerTimes.format(it, twelve) } ?: "--:--", cx, card.top + 134f,
                paint(withAlpha(WHITE, fade), 80f, regular, Paint.Align.CENTER))
            val small = when {
                col.iqamah != null && col.athan != null -> "Adhan " + PrayerTimes.format(col.athan, twelve)
                // Only the adhan is known (the mosque page doesn't list iqamah).
                col.iqamah == null && col.athan != null -> "Adhan"
                else -> ""
            }
            c.drawText(small, cx, card.top + 178f, paint(withAlpha(MUTED, fade), 26f, regular, Paint.Align.CENTER))
        }
        return bmp
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(Color.alpha(color) * alpha / 255, Color.red(color), Color.green(color), Color.blue(color))

    private fun mosqueTitle(): String {
        val slug = Prefs.mosque.trim('/').substringAfterLast('/')
        return slug.split('-', '_', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun prettyDay(day: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(day)
        SimpleDateFormat("d MMM", Locale.UK).format(d!!)
    } catch (_: Exception) { day }

    private fun drawBackground(context: Context, c: Canvas) {
        val photo = BackgroundImage.load(context, W, H)
        if (photo != null) {
            // Centre-crop the photo, then darken it a little for legibility.
            val scale = max(W.toFloat() / photo.width, H.toFloat() / photo.height)
            val sw = (W / scale).toInt(); val sh = (H / scale).toInt()
            val sx = (photo.width - sw) / 2; val sy = (photo.height - sh) / 2
            c.drawBitmap(photo, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
            val shadeTop = if (Prefs.panelInLowerHalf) 0.15f else 0.55f
            val shadeBottom = if (Prefs.panelInLowerHalf) 0.55f else 0.25f
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                    Color.argb((shadeTop * 255).toInt(), 0, 0, 0), Color.argb((shadeBottom * 255).toInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP)
            })
            return
        }
        // Deep green gradient with a faint eight-pointed-star lattice.
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                Color.rgb(0x0F, 0x3D, 0x38), Color.rgb(0x06, 0x16, 0x1C), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = RadialGradient(W * 0.78f, H * 0.18f, W * 0.6f,
                Color.argb(0x33, 0xE8, 0xC2, 0x6A), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(0x16, 0xFF, 0xFF, 0xFF)
        }
        val step = 120f
        var y = 0f
        var row = 0
        while (y < H + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < W + step) {
                c.drawPath(starPath(x, y, 34f), star)
                x += step
            }
            y += step * 0.866f
            row++
        }
    }

    /** Eight-pointed star made of two overlapping squares. */
    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (sq in 0..1) {
            val base = sq * Math.PI / 4
            for (k in 0..3) {
                val a = base + k * Math.PI / 2
                val px = cx + r * cos(a).toFloat()
                val py = cy + r * sin(a).toFloat()
                if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
        }
        return p
    }
}
