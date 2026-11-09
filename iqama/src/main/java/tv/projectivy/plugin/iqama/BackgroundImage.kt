package tv.projectivy.plugin.iqama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional photo behind the times. It comes either from a link (downloaded once) or from a
 * picture chosen in a file explorer on the TV (copied in once). Either way the plugin keeps its
 * own copy, so the original can be moved or the USB stick removed afterwards.
 */
object BackgroundImage {
    private const val TAG = "IqamaBackground"
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun file(context: Context) = File(context.filesDir, "background.img")
    private fun sourceFile(context: Context) = File(context.filesDir, "background.src")

    /** True if a background (from a link or a file) is set. */
    fun isSet(): Boolean = Prefs.backgroundUrl.isNotBlank() || Prefs.backgroundFile.isNotBlank()

    /** Downloads the linked image if it changed or is missing. Call off the main thread. */
    fun refresh(context: Context) {
        if (Prefs.backgroundFile.isNotBlank()) return  // a chosen file is already copied in
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
            if (conn.inputStream.use { copyIn(context, it) }) src.writeText(url)
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't download background", e)
        }
    }

    /**
     * Copies a picture chosen in a file explorer into the plugin. Call off the main thread.
     * Returns null on success, or a short reason it failed.
     */
    fun importFile(context: Context, uri: Uri): String? {
        return try {
            val name = displayName(context, uri)
            val ok = context.contentResolver.openInputStream(uri)?.use { copyIn(context, it) } ?: false
            if (!ok) return "That file isn't a picture the TV can open"
            Prefs.backgroundUrl = ""
            Prefs.backgroundFile = name
            sourceFile(context).writeText("file:$name")
            null
        } catch (e: SecurityException) {
            "PERMISSION"
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read chosen file", e)
            e.message ?: "Couldn't read that file"
        }
    }

    /** Removes the background (back to the green pattern). */
    fun clear(context: Context) {
        Prefs.backgroundUrl = ""
        Prefs.backgroundFile = ""
        file(context).delete()
        sourceFile(context).delete()
    }

    /** Streams [input] to a temp file, checks it's a decodable picture, then swaps it in. */
    private fun copyIn(context: Context, input: InputStream): Boolean {
        val tmp = File(context.filesDir, "background.tmp")
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) { tmp.delete(); error("Picture is too large (max 25 MB)") }
                out.write(buf, 0, n)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { tmp.delete(); return false }
        val f = file(context)
        f.delete()
        return tmp.renameTo(f)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            val c = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0)
                } finally {
                    c.close()
                }
            }
        } catch (_: Exception) {
        }
        if (!name.isNullOrBlank()) return name
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "picture"
    }

    /** Loads the stored image scaled to roughly [targetW] wide, or null. */
    fun load(context: Context, targetW: Int, targetH: Int): Bitmap? {
        if (!isSet()) return null
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
