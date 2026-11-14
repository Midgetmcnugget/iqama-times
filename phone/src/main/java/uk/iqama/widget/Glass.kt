package uk.iqama.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * The TV plugin's "liquid glass" look, scaled for a phone widget ([u] = pixels per dp).
 * If a background picture is set it is frosted (blurred and slightly saturated) behind the
 * glass; the panel is then tinted, lit with a soft top sheen, an inner glow and a bright,
 * uneven rim that catches the light on opposite corners.
 */
object Glass {

    private fun white(alpha: Int) = Color.argb(alpha, 255, 255, 255)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Draws the panel into [rect]. [backdrop] (optional) is the background picture, already
     * cropped to the size of the bitmap [c] draws into. [tint] and [tintAlpha] (0–255) colour
     * the glass.
     */
    fun drawPanel(c: Canvas, rect: RectF, radius: Float, u: Float, backdrop: Bitmap?, tint: Int, tintAlpha: Int) {
        // Soft drop shadow so the glass lifts off the home screen.
        c.drawRoundRect(
            RectF(rect.left + 2 * u, rect.top + 4 * u, rect.right - 2 * u, rect.bottom + 4 * u), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(0x55, 0, 0, 0)
                maskFilter = BlurMaskFilter(6 * u, BlurMaskFilter.Blur.NORMAL)
            }
        )

        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        // Clear the shadow under the glass so a see-through panel shows the wallpaper, not grey.
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        // Frosted, slightly more vivid picture behind the glass.
        if (backdrop != null) {
            val frosted = blur(backdrop, levels = 5)
            val vivid = ColorMatrix().apply { setSaturation(1.35f) }
            c.drawBitmap(frosted, null, Rect(0, 0, c.width, c.height), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(vivid)
            })
            if (frosted !== backdrop) frosted.recycle()
        }

        // Tint in the chosen colour and transparency, plus a whisper of milky white.
        c.drawRect(rect, Paint().apply { color = withAlpha(tint, tintAlpha) })
        c.drawRect(rect, Paint().apply { color = white(0x10) })

        // Sheen across the upper half.
        c.drawRect(rect, Paint().apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
                white(0x2C), white(0x00), Shader.TileMode.CLAMP)
        })

        // Inner glow hugging the edge – the "thickness" of the glass.
        val inner = RectF(rect).apply { inset(2 * u, 2 * u) }
        c.drawRoundRect(inner, radius - 2 * u, radius - 2 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4 * u; color = white(0x16)
            maskFilter = BlurMaskFilter(3 * u, BlurMaskFilter.Blur.NORMAL)
        })
        c.restore()

        // Light-catching rim: bright top-left and bottom-right, faint in between.
        val rim = RectF(rect).apply { inset(0.6f * u, 0.6f * u) }
        c.drawRoundRect(rim, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * u
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(white(0xD8), white(0x40), white(0x14), white(0x40), white(0xA0)),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** The timetable strip's flat panel: optional frosted picture, then the tint. No border. */
    fun drawFlat(c: Canvas, rect: RectF, radius: Float, backdrop: Bitmap?, tint: Int, tintAlpha: Int) {
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (backdrop != null) {
            val frosted = blur(backdrop, levels = 4)
            c.drawBitmap(frosted, null, Rect(0, 0, c.width, c.height), Paint(Paint.FILTER_BITMAP_FLAG))
            if (frosted !== backdrop) frosted.recycle()
        }
        c.drawRect(rect, Paint().apply { color = withAlpha(tint, tintAlpha) })
        c.restore()
    }

    /** The strip's flat banner: a slightly lighter (or darker, on light colours) band. */
    fun drawFlatCard(c: Canvas, rect: RectF, radius: Float, lightBackground: Boolean) {
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lightBackground) Color.argb(0x14, 0, 0, 0) else white(0x24)
        })
    }

    /** A smaller glass tile inside the panel. [accent] (gold) tints the highlighted tile. */
    fun drawCard(c: Canvas, rect: RectF, radius: Float, u: Float, accent: Int?) {
        val base = accent ?: Color.WHITE
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (accent != null) withAlpha(accent, 0x38) else white(0x12)
        })
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.5f,
                withAlpha(base, 0x22), withAlpha(base, 0x00), Shader.TileMode.CLAMP)
        })
        val rim = RectF(rect).apply { inset(0.5f * u, 0.5f * u) }
        c.drawRoundRect(rim, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (accent != null) 1.5f else 0.8f) * u
            shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                withAlpha(base, if (accent != null) 0xFF else 0x70),
                withAlpha(base, if (accent != null) 0x90 else 0x18),
                Shader.TileMode.CLAMP)
        })
    }

    /** Cheap, smooth blur: halve the image [levels] times with filtering, then scale back up. */
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
