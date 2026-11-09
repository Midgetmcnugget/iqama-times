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

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
                Column(key, PrayerTimes.label(key), p?.athan, p?.iqamah)
            }
        }
    }

    fun render(context: Context, now: Calendar): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBackground(context, c)

        val prayers = PrayerTimes.fromJson(Prefs.timesJson)
        val twelve = Prefs.clock12h

        // Panel geometry
        val panelW = 1680f
        val panelH = 330f
        val left = (W - panelW) / 2f
        val top = if (Prefs.positionBottom) H - 90f - panelH else 110f
        val panel = RectF(left, top, left + panelW, top + panelH)

        // Soft dark backing so the text reads on any background
        c.drawRoundRect(panel, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x8C, 0x04, 0x10, 0x12) })
        c.drawRoundRect(panel, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(0x33, 0xFF, 0xFF, 0xFF)
        })

        val pad = 40f
        val headerY = top + pad + 34f
        val title = mosqueTitle()
        c.drawText(title, left + pad, headerY, paint(WHITE, 38f, medium))

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (prayers.isEmpty()) {
            val msg = if (Prefs.lastError.isNotBlank()) "Couldn't load times: ${Prefs.lastError}"
                      else "Loading iqamah times from Masjidbox…"
            c.drawText(msg, panel.centerX(), panel.centerY() + 40f, paint(MUTED, 40f, regular, Paint.Align.CENTER))
            return bmp
        }

        val cols = columns(prayers, now)
        val nextIdx = cols.indexOfFirst { col -> col.sortTime?.let { it > nowMin } == true }

        // Header right: next iqamah
        val nextText = if (nextIdx >= 0) {
            val col = cols[nextIdx]
            "Next iqamah  ·  ${col.label} ${PrayerTimes.format(col.iqamah ?: col.athan!!, twelve)}"
        } else {
            val fajr = cols.firstOrNull()
            if (fajr?.sortTime != null) "Next  ·  Fajr tomorrow" else ""
        }
        c.drawText(nextText, panel.right - pad, headerY, paint(GOLD, 36f, medium, Paint.Align.RIGHT))

        // Note if the times are from an earlier day (e.g. TV offline)
        if (Prefs.fetchedDay.isNotBlank() && Prefs.fetchedDay != MasjidboxFetcher.today()) {
            val note = "times from " + prettyDay(Prefs.fetchedDay)
            val titleW = paint(WHITE, 38f, medium).measureText(title)
            c.drawText(note, left + pad + titleW + 24f, headerY, paint(MUTED, 26f, regular))
        }

        // Cards
        val gap = 22f
        val cardTop = headerY + 34f
        val cardH = panel.bottom - pad + 8f - cardTop
        val cardW = (panelW - 2 * pad - gap * (cols.size - 1)) / cols.size
        cols.forEachIndexed { i, col ->
            val x = left + pad + i * (cardW + gap)
            val card = RectF(x, cardTop, x + cardW, cardTop + cardH)
            val isNext = i == nextIdx
            val passed = col.sortTime?.let { it <= nowMin } == true && !isNext
            if (isNext) {
                c.drawRoundRect(card, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 0xE8, 0xC2, 0x6A) })
                c.drawRoundRect(card, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f; color = GOLD
                })
            } else {
                c.drawRoundRect(card, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x1A, 0xFF, 0xFF, 0xFF) })
            }
            val fade = if (passed) 0x80 else 0xFF
            val cx = card.centerX()
            c.drawText(col.label.uppercase(Locale.UK), cx, card.top + 50f,
                paint(withAlpha(if (isNext) GOLD else MUTED, fade), 28f, medium, Paint.Align.CENTER).apply { letterSpacing = 0.12f })
            val big = col.iqamah ?: col.athan
            c.drawText(big?.let { PrayerTimes.format(it, twelve) } ?: "--:--", cx, card.top + 134f,
                paint(withAlpha(WHITE, fade), 80f, light, Paint.Align.CENTER))
            val small = when {
                col.iqamah != null && col.athan != null -> "Adhan " + PrayerTimes.format(col.athan, twelve)
                col.iqamah == null && col.athan != null -> "Start time"
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
            val shadeTop = if (Prefs.positionBottom) 0.15f else 0.55f
            val shadeBottom = if (Prefs.positionBottom) 0.55f else 0.25f
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
