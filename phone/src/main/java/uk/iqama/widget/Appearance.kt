package uk.iqama.widget

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils

/** Background colour choices for the widget, and the text colours that read well on each. */
object Appearance {

    data class Swatch(val id: String, val label: String, val color: Int)

    /** "theme" follows the phone's wallpaper colours (Android 12+). */
    const val THEME = "theme"

    val SWATCHES = listOf(
        Swatch("teal", "Teal", Color.rgb(0x10, 0x33, 0x2F)),
        Swatch("navy", "Navy", Color.rgb(0x1B, 0x24, 0x33)),
        Swatch("black", "Black", Color.rgb(0x00, 0x00, 0x00)),
        Swatch("charcoal", "Charcoal", Color.rgb(0x2E, 0x2E, 0x30)),
        Swatch("ocean", "Blue", Color.rgb(0x16, 0x3E, 0x6B)),
        Swatch("forest", "Green", Color.rgb(0x1E, 0x46, 0x2C)),
        Swatch("plum", "Plum", Color.rgb(0x3E, 0x22, 0x47)),
        Swatch("maroon", "Maroon", Color.rgb(0x52, 0x1E, 0x24)),
        Swatch("sand", "Sand", Color.rgb(0xEF, 0xE6, 0xD2)),
        Swatch("white", "White", Color.rgb(0xFF, 0xFF, 0xFF)),
    )

    /** The phone's theme colour (dark shade of the wallpaper accent), or teal on older phones. */
    fun themeColor(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_800)
        else SWATCHES.first().color

    fun colorFor(context: Context, id: String): Int =
        if (id == THEME) themeColor(context) else (SWATCHES.firstOrNull { it.id == id } ?: SWATCHES.first()).color

    /** Text colours for a background: white on dark colours, near-black on light ones. */
    data class Palette(
        val text: Int, val muted: Int, val faded: Int, val fadedMuted: Int,
        val lightBackground: Boolean
    )

    fun paletteFor(background: Int, opacityPercent: Int): Palette {
        // A very see-through background shows the wallpaper, which is usually darker, so only
        // switch to dark text when the colour is light and fairly solid.
        val light = ColorUtils.calculateLuminance(background) > 0.5 && opacityPercent >= 45
        return if (light) Palette(
            text = Color.rgb(0x15, 0x1B, 0x22),
            muted = Color.argb(0xB3, 0x15, 0x1B, 0x22),
            faded = Color.argb(0x80, 0x15, 0x1B, 0x22),
            fadedMuted = Color.argb(0x5A, 0x15, 0x1B, 0x22),
            lightBackground = true
        ) else {
            // Fade passed prayers less when the wallpaper shows through, so they stay readable.
            val clear = opacityPercent < 50
            Palette(
                text = Color.WHITE,
                muted = Color.argb(if (clear) 0xCC else 0xB3, 0xFF, 0xFF, 0xFF),
                faded = Color.argb(if (clear) 0xA6 else 0x80, 0xFF, 0xFF, 0xFF),
                fadedMuted = Color.argb(if (clear) 0x8C else 0x5A, 0xFF, 0xFF, 0xFF),
                lightBackground = false
            )
        }
    }
}
