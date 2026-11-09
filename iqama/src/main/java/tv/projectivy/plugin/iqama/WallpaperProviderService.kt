package tv.projectivy.plugin.iqama

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.util.Calendar

class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            if (event != null && event !is Event.TimeElapsed) return emptyList()
            return try {
                val ctx = applicationContext
                if (MasjidboxFetcher.isStale()) {
                    MasjidboxFetcher.refresh(ctx) { ok -> if (ok) notifyProjectivy(ctx) }
                }
                val bitmap = WallpaperRenderer.render(ctx, Calendar.getInstance())
                val uri = WallpaperImageProvider.save(ctx, bitmap)
                bitmap.recycle()
                listOf(
                    Wallpaper(
                        uri.toString(),
                        WallpaperType.IMAGE,
                        WallpaperDisplayMode.CROP,
                        title = "Iqamah times",
                        source = Prefs.mosqueUrl,
                        author = "Masjidbox"
                    )
                )
            } catch (e: Throwable) {
                Log.e("IqamaService", "Render failed", e)
                emptyList()
            }
        }

        override fun getPreferences(): String = Prefs.export()

        override fun setPreferences(params: String) {
            Prefs.import(params)
        }
    }

    companion object {
        private const val PROJECTIVY = "com.spocky.projengmenu"

        /** Tells Projectivy to ask for a new wallpaper now (new times or changed settings). */
        fun notifyProjectivy(context: Context, prefsChanged: Boolean = false) {
            val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
                `package` = PROJECTIVY
                putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, context.getString(R.string.plugin_uuid))
                putExtra(
                    WallpaperProviderContract.EXTRA_UPDATE_REASON,
                    if (prefsChanged) WallpaperProviderContract.UpdateReason.PREFS_CHANGED
                    else WallpaperProviderContract.UpdateReason.DATA_CHANGED
                )
            }
            context.sendBroadcast(intent)
        }
    }
}
