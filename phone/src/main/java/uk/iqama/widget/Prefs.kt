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

        val url: String get() = "https://masjidbox.com/prayer-times/$slug"

        /** The first version kept one set of offsets for all mosques; carry them over once. */
        private fun legacyOffset(key: String, def: Int): Int =
            if (slug == defaultMosque) p.getInt(key, def) else def
    }

    companion object {
        const val DEFAULT_MOSQUE = "masjid-al-falah-coventry"

        /** "https://masjidbox.com/prayer-times/chingford-mosque?x" -> "chingford-mosque". */
        fun slug(input: String): String {
            val t = input.trim().substringBefore('?').substringBefore('#').trim('/')
            return t.substringAfterLast('/').lowercase().ifBlank { DEFAULT_MOSQUE }
        }

        fun titleFromSlug(slug: String): String =
            slug.split('-', '_', ' ').filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
