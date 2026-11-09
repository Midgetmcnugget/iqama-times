package tv.projectivy.plugin.iqama

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * A "liquid glass" look in the spirit of Apple's design: the background behind the panel is
 * blurred and slightly saturated, then lit with a soft top sheen and a bright, uneven rim
 * that catches the light on opposite corners.
 */
object Glass {

    private fun white(alpha: Int) = Color.argb(alpha, 255, 255, 255)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Draws the main frosted panel. [bmp] must be the bitmap [c] draws into. */
    fun drawPanel(bmp: Bitmap, c: Canvas, rect: RectF, radius: Float) {
        // 1. Take a blurred copy of what's behind the panel (before anything else is drawn on it).
        val margin = 48
        val sx = max(0, rect.left.toInt() - margin)
        val sy = max(0, rect.top.toInt() - margin)
        val ex = min(bmp.width, rect.right.toInt() + margin)
        val ey = min(bmp.height, rect.bottom.toInt() + margin)
        val region = Bitmap.createBitmap(bmp, sx, sy, ex - sx, ey - sy)
        val blurred = blur(region, levels = 5)
        if (blurred !== region) region.recycle()

        // 2. Soft drop shadow so the glass lifts off the background.
        c.drawRoundRect(
            RectF(rect.left + 6f, rect.top + 22f, rect.right - 6f, rect.bottom + 22f), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(0x70, 0, 0, 0)
                maskFilter = BlurMaskFilter(42f, BlurMaskFilter.Blur.NORMAL)
            }
        )

        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)

        // 3. Frosted, slightly more vivid background.
        val vivid = ColorMatrix().apply { setSaturation(1.35f) }
        c.drawBitmap(blurred, sx.toFloat(), sy.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(vivid)
        })
        blurred.recycle()

        // 4. Tint: a whisper of white for the milky glass, a touch of dark so white text stays crisp.
        c.drawRect(rect, Paint().apply { color = white(0x16) })
        c.drawRect(rect, Paint().apply { color = Color.argb(0x2E, 0, 0, 0) })

        // 5. Sheen across the upper half.
        c.drawRect(rect, Paint().apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
                white(0x30), white(0x00), Shader.TileMode.CLAMP)
        })

        // 6. Inner glow hugging the edge – the "thickness" of the glass.
        val inner = RectF(rect).apply { inset(5f, 5f) }
        c.drawRoundRect(inner, radius - 5f, radius - 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; color = white(0x14)
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        })
        c.restore()

        // 7. Light-catching rim: bright top-left and bottom-right, faint in between.
        val rim = RectF(rect).apply { inset(1.25f, 1.25f) }
        c.drawRoundRect(rim, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(white(0xD8), white(0x40), white(0x14), white(0x40), white(0xA0)),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** A smaller glass tile inside the panel. [accent] (e.g. gold) tints the highlighted tile. */
    fun drawCard(c: Canvas, rect: RectF, radius: Float, accent: Int?) {
        val base = accent ?: Color.WHITE
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (accent != null) withAlpha(accent, 0x38) else white(0x12)
        })
        // Sheen on the top half
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.5f,
                withAlpha(base, 0x22), withAlpha(base, 0x00), Shader.TileMode.CLAMP)
        })
        // Rim: brighter on top, fading towards the bottom
        val rim = RectF(rect).apply { inset(1f, 1f) }
        c.drawRoundRect(rim, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (accent != null) 3f else 1.5f
            shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                withAlpha(base, if (accent != null) 0xFF else 0x70),
                withAlpha(base, if (accent != null) 0x90 else 0x18),
                Shader.TileMode.CLAMP)
        })
    }

    /**
     * Cheap, smooth blur: halve the image [levels] times with filtering, then double it back up.
     * The repeated bilinear passes approximate a Gaussian blur.
     */
    private fun blur(src: Bitmap, levels: Int): Bitmap {
        val sizes = ArrayList<Pair<Int, Int>>()
        var w = src.width
        var h = src.height
        var cur = src
        repeat(levels) {
            sizes.add(w to h)
            w = max(1, w / 2); h = max(1, h / 2)
            val next = Bitmap.createScaledBitmap(cur, w, h, true)
            if (next !== cur && cur !== src) cur.recycle()
            cur = next
        }
        for (i in sizes.indices.reversed()) {
            val (tw, th) = sizes[i]
            val next = Bitmap.createScaledBitmap(cur, tw, th, true)
            if (next !== cur && cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }
}
