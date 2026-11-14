package uk.iqama.widget

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fonts for the glass widget, the same way as the TV plugin: any Google Fonts family by name,
 * downloaded once through Google Play services and cached. A blank name = the phone's Roboto.
 * Drawing never waits for a download: it uses Roboto until the font arrives, then redraws.
 */
object Fonts {
    private const val TAG = "IqamaWidgetFonts"

    /** Same shortlist as the TV plugin. Any other Google Fonts name can be typed in. */
    val CHOICES = listOf(
        "Inter", "Poppins", "Montserrat", "Lexend", "Outfit", "Manrope", "DM Sans",
        "Figtree", "Plus Jakarta Sans", "Nunito", "Rubik", "Quicksand", "Urbanist",
        "Work Sans", "Open Sans", "Lato", "Raleway", "Josefin Sans", "Comfortaa", "Exo 2"
    )

    private val cache = ConcurrentHashMap<String, Typeface>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private const val RETRY_FAILED_MS = 30 * 60 * 1000L
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val systemRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val systemMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val systemBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Regular weight of [name], or Roboto while it downloads (then the widget redraws). */
    fun regular(context: Context, name: String): Typeface = get(context, name, 400) ?: systemRegular

    /** Medium weight, for labels and the mosque name. */
    fun medium(context: Context, name: String): Typeface = get(context, name, 500) ?: systemMedium

    /** Bold weight, for the "Bold times" option. */
    fun bold(context: Context, name: String): Typeface = get(context, name, 700) ?: systemBold

    private fun get(context: Context, name: String, weight: Int): Typeface? {
        if (name.isBlank()) return null
        val key = "$name@$weight"
        val cached = cache[key]
        if (cached != null) return cached
        val failedAt = failed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_FAILED_MS) return null
        if (loading.add(key)) {
            val app = context.applicationContext
            worker.execute {
                val ok = fetch(app, name, weight) != null
                loading.remove(key)
                if (ok) main.post { IqamaWidget.updateAll(app) }
            }
        }
        return null
    }

    /**
     * Downloads [name] now and says whether it worked. Blocks: call off the main thread.
     * Used by settings to check a typed-in name before switching to it.
     */
    fun prepare(context: Context, name: String): Boolean {
        if (name.isBlank()) return true
        failed.remove("$name@400"); failed.remove("$name@500")
        return fetch(context, name, 400) != null && fetch(context, name, 500) != null
    }

    private fun fetch(context: Context, name: String, weight: Int): Typeface? {
        val key = "$name@$weight"
        val cached = cache[key]
        if (cached != null) return cached
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
            face
        } catch (e: Throwable) {
            Log.w(TAG, "Font '$name' failed", e)
            failed[key] = System.currentTimeMillis()
            null
        }
    }
}
