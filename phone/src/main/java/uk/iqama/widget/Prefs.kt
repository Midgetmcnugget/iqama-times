package uk.iqama.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings and cached times.
 *  - Each widget has its own mosque (falls back to the default mosque).
 *  - Times, the mosque's display name and the iqamah offsets are stored per mosque.
 *  - Clock style and the sunrise column apply to all widgets.
 */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("iqama_widget", Context.MODE_PRIVATE)

    // ---- global ----

    /** Mosque used by widgets that haven't been given their own. */
    var defaultMosque: String
        get() = p.getString("mosque", DEFAULT_MOSQUE)?.takeIf { it.isNotBlank() } ?: DEFAULT_MOSQUE
        set(v) = p.edit().putString("mosque", slug(v)).apply()

    var clock12h: Boolean
        get() = p.getBoolean("clock_12h", false)
        set(v) = p.edit().putBoolean("clock_12h", v).apply()

    var showSunrise: Boolean
        get() = p.getBoolean("show_sunrise", true)
        set(v) = p.edit().putBoolean("show_sunrise", v).apply()

    var showName: Boolean
        get() = p.getBoolean("show_name", true)
        set(v) = p.edit().putBoolean("show_name", v).apply()

    /** Background colour: a swatch id from [Appearance.SWATCHES], or "theme". */
    var background: String
        get() = p.getString("background", "teal") ?: "teal"
        set(v) = p.edit().putString("background", v).apply()

    /** How solid the background is, 0 (clear) to 100 (solid). */
    var opacity: Int
        get() = p.getInt("opacity", 85)
        set(v) = p.edit().putInt("opacity", v.coerceIn(0, 100)).apply()

    /** Widget look: "glass" (the TV plugin's liquid glass tiles) or "strip" (timetable strip). */
    var style: String
        get() = p.getString("style", STYLE_GLASS) ?: STYLE_GLASS
        set(v) = p.edit().putString("style", v).apply()

    /** Google Fonts family for the glass look, e.g. "Poppins". Blank = Roboto. */
    var fontName: String
        get() = p.getString("font_name", "") ?: ""
        set(v) = p.edit().putString("font_name", v.trim()).apply()

    /** Name of the background picture behind the glass ("" = none). */
    var backgroundPicture: String
        get() = p.getString("background_picture", "") ?: ""
        set(v) = p.edit().putString("background_picture", v).apply()

    /** Text size: 0 = small, 1 = medium, 2 = large. */
    var textSize: Int
        get() = p.getInt("text_size", 1)
        set(v) = p.edit().putInt("text_size", v.coerceIn(0, 2)).apply()

    /** Draw the adhan times in bold. */
    var boldTimes: Boolean
        get() = p.getBoolean("bold_times", false)
        set(v) = p.edit().putBoolean("bold_times", v).apply()

    /** Fade prayers that have already passed. */
    var fadePassed: Boolean
        get() = p.getBoolean("fade_passed", true)
        set(v) = p.edit().putBoolean("fade_passed", v).apply()

    /** Soft outline round the text, for see-through widgets and busy pictures. */
    var textOutline: Boolean
        get() = p.getBoolean("text_outline", false)
        set(v) = p.edit().putBoolean("text_outline", v).apply()

    /** Prayer names: "en", "ar" or "both" (stacked). */
    var nameLanguage: String
        get() = p.getString("name_language", "en") ?: "en"
        set(v) = p.edit().putString("name_language", v).apply()

    /** Mosques used before, most recent first, for the picker. */
    var recentMosques: List<String>
        get() {
            val saved = p.getString("recent", null)?.split('\n')?.filter { it.isNotBlank() }
            return (saved ?: listOf(DEFAULT_MOSQUE, "chingford-mosque")).distinct()
        }
        set(v) = p.edit().putString("recent", v.distinct().take(12).joinToString("\n")).apply()

    fun rememberMosque(slugOrLink: String) {
        val s = slug(slugOrLink)
        recentMosques = listOf(s) + recentMosques.filter { it != s }
    }

    fun forgetMosque(slugOrLink: String) {
        val s = slug(slugOrLink)
        recentMosques = recentMosques.filter { it != s }
    }

    // ---- per widget ----

    fun mosqueFor(widgetId: Int): String =
        p.getString("w_mosque_$widgetId", null)?.takeIf { it.isNotBlank() } ?: defaultMosque

    fun setMosqueFor(widgetId: Int, slugOrLink: String) =
        p.edit().putString("w_mosque_$widgetId", slug(slugOrLink)).apply()

    fun forgetWidget(widgetId: Int) = p.edit().remove("w_mosque_$widgetId").apply()

    // ---- per mosque ----

    fun mosque(slug: String) = Mosque(slug(slug))

    inner class Mosque(val slug: String) {
        private fun k(name: String) = "${name}_$slug"

        /** Name as written on the mosque's page, or one made from the address. */
        var name: String
            get() = p.getString(k("name"), null)?.takeIf { it.isNotBlank() } ?: titleFromSlug(slug)
            set(v) = p.edit().putString(k("name"), v).apply()

        var timesJson: String
            get() = p.getString(k("times"), "") ?: ""
            set(v) = p.edit().putString(k("times"), v).apply()

        var fetchedAt: Long
            get() = p.getLong(k("fetched_at"), 0L)
            set(v) = p.edit().putLong(k("fetched_at"), v).apply()

        /** Day (yyyy-MM-dd) the stored times are for. */
        var fetchedDay: String
            get() = p.getString(k("fetched_day"), "") ?: ""
            set(v) = p.edit().putString(k("fetched_day"), v).apply()

        var lastError: String
            get() = p.getString(k("error"), "") ?: ""
            set(v) = p.edit().putString(k("error"), v).apply()

        var pageSnippet: String
            get() = p.getString(k("snippet"), "") ?: ""
            set(v) = p.edit().putString(k("snippet"), v).apply()

        /** Minutes after adhan for mosques whose page only lists adhan; -1 = off. */
        var iqamahOffset: Int
            get() = p.getInt(k("iqamah_offset"), legacyOffset("iqamah_offset", -1))
            set(v) = p.edit().putInt(k("iqamah_offset"), v).apply()

        var maghribOffset: Int
            get() = p.getInt(k("maghrib_offset"), legacyOffset("maghrib_offset", 0))
            set(v) = p.edit().putInt(k("maghrib_offset"), v).apply()

        /** True if this mosque's times come from Mawaqit rather than Masjidbox. */
        val isMawaqit: Boolean get() = Mawaqit.isMawaqit(slug)

        val url: String get() = if (isMawaqit) Mawaqit.pageUrl(slug) else "https://masjidbox.com/prayer-times/$slug"

        /** The first version kept one set of offsets for all mosques; carry them over once. */
        private fun legacyOffset(key: String, def: Int): Int =
            if (slug == defaultMosque) p.getInt(key, def) else def
    }

    companion object {
        const val DEFAULT_MOSQUE = "masjid-al-falah-coventry"
        const val STYLE_GLASS = "glass"
        const val STYLE_STRIP = "strip"

        /**
         * The key a mosque is stored under:
         *  "https://masjidbox.com/prayer-times/chingford-mosque" or "chingford-mosque" -> "chingford-mosque";
         *  "https://mawaqit.net/en/some-mosque" or "mawaqit:some-mosque" -> "mawaqit:some-mosque".
         */
        fun slug(input: String): String {
            val raw = input.trim()
            val mawaqit = raw.startsWith(Mawaqit.PREFIX, ignoreCase = true) || raw.contains("mawaqit.net", ignoreCase = true)
            val t = raw.removePrefix(Mawaqit.PREFIX).removePrefix(Mawaqit.PREFIX.uppercase())
                .substringBefore('?').substringBefore('#').trim('/')
            val last = t.substringAfterLast('/').lowercase()
            if (last.isBlank()) return DEFAULT_MOSQUE
            return if (mawaqit) Mawaqit.PREFIX + last else last
        }

        /** A name typed without a link: [mawaqit] says which service it is on. */
        fun slug(input: String, mawaqit: Boolean): String {
            val s = slug(input)
            val looksLikeLink = input.contains("://") || input.contains(".net") || input.contains(".com") ||
                input.trim().startsWith(Mawaqit.PREFIX, ignoreCase = true)
            return if (mawaqit && !looksLikeLink && !Mawaqit.isMawaqit(s)) Mawaqit.PREFIX + s else s
        }

        fun titleFromSlug(slug: String): String =
            Mawaqit.slugOf(slug).split('-', '_', ' ').filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
