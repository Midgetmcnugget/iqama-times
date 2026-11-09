package tv.projectivy.plugin.iqama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Optional photo behind the times, downloaded once per refresh and kept on disk. */
object BackgroundImage {
    private const val TAG = "IqamaBackground"
    private const val MAX_BYTES = 15L * 1024 * 1024

    private fun file(context: Context) = File(context.filesDir, "background.img")
    private fun sourceFile(context: Context) = File(context.filesDir, "background.src")

    /** Downloads the configured image if it changed or is missing. Call off the main thread. */
    fun refresh(context: Context) {
        val url = Prefs.backgroundUrl
        val f = file(context)
        val src = sourceFile(context)
        if (url.isBlank()) {
            f.delete(); src.delete(); return
        }
        if (f.exists() && src.exists() && src.readText() == url) return
        try {
            var conn = URL(url).openConnection() as HttpURLConnection
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), loc).openConnection() as HttpURLConnection
                redirects++
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode}"); conn.disconnect(); return
            }
            val tmp = File(context.filesDir, "background.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("Image too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            if (tmp.renameTo(f)) src.writeText(url)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't download background", e)
        }
    }

    /** Loads the stored image scaled to roughly [targetW] wide, or null. */
    fun load(context: Context, targetW: Int, targetH: Int): Bitmap? {
        if (Prefs.backgroundUrl.isBlank()) return null
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }
}
