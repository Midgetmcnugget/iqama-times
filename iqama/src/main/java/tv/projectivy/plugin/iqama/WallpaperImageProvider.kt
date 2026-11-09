package tv.projectivy.plugin.iqama

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider that hands Projectivy the rendered wallpaper PNGs.
 * Only files written by this plugin into its own "wallpapers" folder can be opened.
 */
class WallpaperImageProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "tv.projectivy.plugin.iqama.images"
        private val NAME = Regex("^iqama-\\d+\\.png$")
        private const val KEEP = 4

        private fun dir(context: Context) = File(context.filesDir, "wallpapers").apply { mkdirs() }

        /** Saves [bitmap] under a new name (so Projectivy doesn't reuse a cached copy) and returns its URI. */
        fun save(context: Context, bitmap: Bitmap): Uri {
            val d = dir(context)
            val name = "iqama-${System.currentTimeMillis()}.png"
            val tmp = File(d, "$name.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val out = File(d, name)
            tmp.renameTo(out)
            // Keep only the newest few images.
            d.listFiles()?.filter { NAME.matches(it.name) }
                ?.sortedByDescending { it.name.removePrefix("iqama-").removeSuffix(".png").toLongOrNull() ?: 0L }
                ?.drop(KEEP)?.forEach { it.delete() }
            d.listFiles()?.filter { it.name.endsWith(".tmp") && it.name != tmp.name }?.forEach { it.delete() }
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("w").appendPath(name).build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!NAME.matches(name)) throw FileNotFoundException()
        val ctx = context ?: throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (!f.exists()) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
