package tv.projectivy.plugin.iqama

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonts for the wallpaper. Like Projectivy's own font option, any Google Fonts family can be
 * used by name; it is downloaded once through Google Play services and cached on the TV.
 * A blank name means the TV's built-in font (Roboto).
 */
object Fonts {
    private const val TAG = "IqamaFonts"

    /** Shortlist shown in settings. Any other Google Fonts name can be typed in. */
    val CHOICES = listOf(
        "Inter", "Poppins", "Montserrat", "Lexend", "Outfit", "Manrope", "DM Sans",
        "Figtree", "Plus Jakarta Sans", "Nunito", "Rubik", "Quicksand", "Urbanist",
        "Work Sans", "Open Sans", "Lato", "Raleway", "Josefin Sans", "Comfortaa", "Exo 2"
    )

    private val cache = ConcurrentHashMap<String, Typeface>()
    private val failed = ConcurrentHashMap<String, Long>()
    private const val RETRY_FAILED_MS = 30 * 60 * 1000L

    private val systemRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val systemMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Regular weight of the chosen font. Blocks while downloading: never call on the main thread. */
    fun regular(context: Context): Typeface = load(context, Prefs.fontName, 400) ?: systemRegular

    /** Medium weight (used for labels and the mosque name). Same threading rule. */
    fun medium(context: Context): Typeface = load(context, Prefs.fontName, 500) ?: systemMedium

    /**
     * Downloads [name] if needed. Returns true if it's available. Call off the main thread.
     * Used by settings to check a typed-in name before using it.
     */
    fun prepare(context: Context, name: String): Boolean {
        if (name.isBlank()) return true
        failed.remove(name)  // a deliberate check always retries
        return load(context, name, 400) != null && load(context, name, 500) != null
    }

    private fun load(context: Context, name: String, weight: Int): Typeface? {
        if (name.isBlank()) return null
        val key = "$name@$weight"
        val cached = cache[key]
        if (cached != null) return cached
        val failedAt = failed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_FAILED_MS) return null
        return try {
            // besteffort: if the family has no 500 weight, use the closest one it has.
            val query = "name=${name.replace("&", "")}&weight=$weight&besteffort=true"
            val request = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                query,
                R.array.com_google_android_gms_fonts_certs
            )
            val result = FontsContractCompat.fetchFonts(context, null, request)
            val face = if (result.statusCode == FontsContractCompat.FontFamilyResult.STATUS_OK &&
                result.fonts.isNotEmpty() &&
                result.fonts.all { it.resultCode == FontsContractCompat.Columns.RESULT_CODE_OK }
            ) FontsContractCompat.buildTypeface(context, null, result.fonts) else null
            if (face != null) cache[key] = face else failed[key] = System.currentTimeMillis()
            if (face == null) Log.w(TAG, "Font '$name' ($weight) not available, status ${result.statusCode}")
            face
        } catch (e: Throwable) {
            Log.w(TAG, "Font '$name' failed", e)
            failed[key] = System.currentTimeMillis()
            null
        }
    }
}
