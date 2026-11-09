package tv.projectivy.plugin.iqama

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Plugin settings and the last times read from Masjidbox, stored in SharedPreferences. */
object Prefs {
    const val DEFAULT_MOSQUE = "masjid-al-falah-coventry"

    private const val FILE = "iqama_prefs"
    private const val KEY_MOSQUE = "mosque"
    private const val KEY_POSITION_BOTTOM = "position_bottom"
    private const val KEY_PANEL_TOP = "panel_top"
    private const val KEY_SHOW_NAME = "show_mosque_name"
    private const val KEY_FONT = "font_name"
    private const val KEY_CLOCK_12H = "clock_12h"
    private const val KEY_BACKGROUND_URL = "background_url"
    private const val KEY_TIMES_JSON = "times_json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_FETCHED_DAY = "fetched_day"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PAGE_SNIPPET = "page_snippet"
    private const val KEY_IQAMAH_OFFSET = "iqamah_offset"
    private const val KEY_MAGHRIB_OFFSET = "maghrib_offset"
    private const val KEY_BACKGROUND_FILE = "background_file"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private val p: SharedPreferences
        get() = prefs ?: error("Prefs.init() not called")

    /** Masjidbox slug (e.g. "masjid-al-falah-coventry") or a full masjidbox.com link. */
    var mosque: String
        get() = p.getString(KEY_MOSQUE, DEFAULT_MOSQUE)?.takeIf { it.isNotBlank() } ?: DEFAULT_MOSQUE
        set(value) = p.edit().putString(KEY_MOSQUE, value.trim()).apply()

    // ---- Panel layout (all in pixels on a 1920x1080 frame) ----
    /** Height of the row of prayer tiles. */
    const val CARD_HEIGHT = 190
    /** Space above and below the tiles inside the panel. */
    const val PANEL_PADDING = 32
    /** Extra height the mosque-name header adds when it is shown. */
    const val HEADER_HEIGHT = 76
    const val PANEL_TOP_DEFAULT = 190
    const val PANEL_TOP_MIN = 20
    const val PANEL_STEP = 20

    /** Panel height: it shrinks to fit when the mosque name is hidden. */
    val panelHeight: Int
        get() = PANEL_PADDING * 2 + CARD_HEIGHT + if (showMosqueName) HEADER_HEIGHT else 0

    /** Lowest the panel can go while staying fully on screen. */
    val panelTopMax: Int
        get() = 1080 - panelHeight - 20

    // Vertical position of the panel: distance of its top edge from the top of the screen.
    var panelTop: Int
        get() {
            if (!p.contains(KEY_PANEL_TOP) && p.getBoolean(KEY_POSITION_BOTTOM, false)) {
                // Carry over the old "bottom of the screen" setting.
                return 1080 - 90 - panelHeight
            }
            return p.getInt(KEY_PANEL_TOP, PANEL_TOP_DEFAULT).coerceIn(PANEL_TOP_MIN, panelTopMax)
        }
        set(value) = p.edit()
            .putInt(KEY_PANEL_TOP, value.coerceIn(PANEL_TOP_MIN, panelTopMax))
            .remove(KEY_POSITION_BOTTOM)
            .apply()

    /** True when the panel sits in the lower half (used to shade a background photo). */
    val panelInLowerHalf: Boolean
        get() = panelTop + panelHeight / 2 > 540

    var showMosqueName: Boolean
        get() = p.getBoolean(KEY_SHOW_NAME, true)
        set(value) = p.edit().putBoolean(KEY_SHOW_NAME, value).apply()

    /** Google Fonts family name, e.g. "Poppins". Blank = the TV's own font (Roboto). */
    var fontName: String
        get() = p.getString(KEY_FONT, "") ?: ""
        set(value) = p.edit().putString(KEY_FONT, value.trim()).apply()

    /**
     * For mosques whose page only lists adhan times: iqamah this many minutes after adhan.
     * -1 = off (show adhan times only).
     */
    var iqamahOffset: Int
        get() = p.getInt(KEY_IQAMAH_OFFSET, -1)
        set(value) = p.edit().putInt(KEY_IQAMAH_OFFSET, value).apply()

    /** Maghrib's own offset (often 0 or 5), used when [iqamahOffset] is on. */
    var maghribOffset: Int
        get() = p.getInt(KEY_MAGHRIB_OFFSET, 0)
        set(value) = p.edit().putInt(KEY_MAGHRIB_OFFSET, value).apply()

    /** Display name of a background picture chosen from a file ("" = none). */
    var backgroundFile: String
        get() = p.getString(KEY_BACKGROUND_FILE, "") ?: ""
        set(value) = p.edit().putString(KEY_BACKGROUND_FILE, value).apply()

    var clock12h: Boolean
        get() = p.getBoolean(KEY_CLOCK_12H, false)
        set(value) = p.edit().putBoolean(KEY_CLOCK_12H, value).apply()

    var backgroundUrl: String
        get() = p.getString(KEY_BACKGROUND_URL, "") ?: ""
        set(value) = p.edit().putString(KEY_BACKGROUND_URL, value.trim()).apply()

    var timesJson: String
        get() = p.getString(KEY_TIMES_JSON, "") ?: ""
        set(value) = p.edit().putString(KEY_TIMES_JSON, value).apply()

    var fetchedAt: Long
        get() = p.getLong(KEY_FETCHED_AT, 0L)
        set(value) = p.edit().putLong(KEY_FETCHED_AT, value).apply()

    /** Day (yyyy-MM-dd) the stored times are for. */
    var fetchedDay: String
        get() = p.getString(KEY_FETCHED_DAY, "") ?: ""
        set(value) = p.edit().putString(KEY_FETCHED_DAY, value).apply()

    /** The part of the page the times were read from, shown in settings for troubleshooting. */
    var pageSnippet: String
        get() = p.getString(KEY_PAGE_SNIPPET, "") ?: ""
        set(value) = p.edit().putString(KEY_PAGE_SNIPPET, value).apply()

    var lastError: String
        get() = p.getString(KEY_LAST_ERROR, "") ?: ""
        set(value) = p.edit().putString(KEY_LAST_ERROR, value).apply()

    /** Full page address for the configured mosque. */
    val mosqueUrl: String
        get() {
            val m = mosque
            return if (m.startsWith("http://") || m.startsWith("https://")) m
            else "https://masjidbox.com/prayer-times/" + m.trim('/').substringAfterLast('/')
        }

    // Projectivy can back up and restore plugin settings through these two.
    fun export(): String = JSONObject().apply {
        put(KEY_MOSQUE, mosque)
        put(KEY_SHOW_NAME, showMosqueName)
        put(KEY_PANEL_TOP, panelTop)
        put(KEY_FONT, fontName)
        put(KEY_CLOCK_12H, clock12h)
        put(KEY_BACKGROUND_URL, backgroundUrl)
        put(KEY_IQAMAH_OFFSET, iqamahOffset)
        put(KEY_MAGHRIB_OFFSET, maghribOffset)
    }.toString()

    fun import(json: String) {
        try {
            val o = JSONObject(json)
            if (o.has(KEY_MOSQUE)) mosque = o.getString(KEY_MOSQUE)
            if (o.has(KEY_SHOW_NAME)) showMosqueName = o.getBoolean(KEY_SHOW_NAME)
            if (o.has(KEY_FONT)) fontName = o.getString(KEY_FONT)
            if (o.has(KEY_PANEL_TOP)) panelTop = o.getInt(KEY_PANEL_TOP)
            else if (o.has(KEY_POSITION_BOTTOM) && o.getBoolean(KEY_POSITION_BOTTOM)) panelTop = 1080 - 90 - panelHeight
            if (o.has(KEY_CLOCK_12H)) clock12h = o.getBoolean(KEY_CLOCK_12H)
            if (o.has(KEY_BACKGROUND_URL)) backgroundUrl = o.getString(KEY_BACKGROUND_URL)
            if (o.has(KEY_IQAMAH_OFFSET)) iqamahOffset = o.getInt(KEY_IQAMAH_OFFSET)
            if (o.has(KEY_MAGHRIB_OFFSET)) maghribOffset = o.getInt(KEY_MAGHRIB_OFFSET)
        } catch (_: Exception) {
            // Ignore malformed backups; keep current settings.
        }
    }
}
