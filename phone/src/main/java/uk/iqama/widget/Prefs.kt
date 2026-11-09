package uk.iqama.widget

import android.content.Context
import android.content.SharedPreferences

/** Widget settings and the last times read from Masjidbox. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("iqama_widget", Context.MODE_PRIVATE)

    /** Masjidbox slug (e.g. "masjid-al-falah-coventry") or a full masjidbox.com link. */
    var mosque: String
        get() = p.getString("mosque", DEFAULT_MOSQUE)?.takeIf { it.isNotBlank() } ?: DEFAULT_MOSQUE
        set(v) = p.edit().putString("mosque", v.trim()).apply()

    var showName: Boolean
        get() = p.getBoolean("show_name", true)
        set(v) = p.edit().putBoolean("show_name", v).apply()

    var clock12h: Boolean
        get() = p.getBoolean("clock_12h", false)
        set(v) = p.edit().putBoolean("clock_12h", v).apply()

    /** Minutes after adhan for mosques whose page only lists adhan; -1 = off. */
    var iqamahOffset: Int
        get() = p.getInt("iqamah_offset", -1)
        set(v) = p.edit().putInt("iqamah_offset", v).apply()

    var maghribOffset: Int
        get() = p.getInt("maghrib_offset", 0)
        set(v) = p.edit().putInt("maghrib_offset", v).apply()

    var timesJson: String
        get() = p.getString("times_json", "") ?: ""
        set(v) = p.edit().putString("times_json", v).apply()

    var fetchedAt: Long
        get() = p.getLong("fetched_at", 0L)
        set(v) = p.edit().putLong("fetched_at", v).apply()

    /** Day (yyyy-MM-dd) the stored times are for. */
    var fetchedDay: String
        get() = p.getString("fetched_day", "") ?: ""
        set(v) = p.edit().putString("fetched_day", v).apply()

    var lastError: String
        get() = p.getString("last_error", "") ?: ""
        set(v) = p.edit().putString("last_error", v).apply()

    var pageSnippet: String
        get() = p.getString("page_snippet", "") ?: ""
        set(v) = p.edit().putString("page_snippet", v).apply()

    val mosqueUrl: String
        get() {
            val m = mosque
            return if (m.startsWith("http://") || m.startsWith("https://")) m
            else "https://masjidbox.com/prayer-times/" + m.trim('/').substringAfterLast('/')
        }

    /** Mosque name for the header, from the page address (e.g. "Masjid Al Falah Coventry"). */
    val mosqueTitle: String
        get() = mosque.trim('/').substringAfterLast('/').split('-', '_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        const val DEFAULT_MOSQUE = "masjid-al-falah-coventry"
    }
}
