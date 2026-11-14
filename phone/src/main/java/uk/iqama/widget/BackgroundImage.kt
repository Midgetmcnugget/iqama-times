package uk.iqama.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlin.math.max

/**
 * Optional picture behind the glass widget (like the TV plugin's background picture). The
 * chosen file is copied into the app, so the original can be moved or deleted afterwards.
 */
object BackgroundImage {
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun file(context: Context) = File(context.filesDir, "widget_background.img")

    /**
     * Copies the picture at [uri] in. Call off the main thread.
     * Returns null on success, or a short reason it failed.
     */
    fun importFrom(context: Context, uri: Uri): String? {
        return try {
            val tmp = File(context.filesDir, "widget_background.tmp")
            val input = context.contentResolver.openInputStream(uri) ?: return "Couldn't open that file"
            var tooLarge = false
            try {
                val out = tmp.outputStream()
                try {
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tooLarge = true; break }
                        out.write(buf, 0, n)
                    }
                } finally {
                    out.close()
                }
            } finally {
                input.close()
            }
            if (tooLarge) { tmp.delete(); return "Picture is too large (max 25 MB)" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { tmp.delete(); return "That file isn't a picture" }
            file(context).delete()
            if (!tmp.renameTo(file(context))) return "Couldn't save the picture"
            Prefs(context).backgroundPicture = displayName(context, uri)
            null
        } catch (e: Exception) {
            e.message ?: "Couldn't read that file"
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        Prefs(context).backgroundPicture = ""
    }

    /** The picture centre-cropped to exactly [w]×[h] pixels, or null if none is set. */
    fun cropped(context: Context, w: Int, h: Int): Bitmap? {
        if (Prefs(context).backgroundPicture.isBlank()) return null
        val f = file(context)
        if (!f.exists() || w <= 0 || h <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
            val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            out
        } catch (e: Throwable) {
            null
        }
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
        return "picture"
    }
}
