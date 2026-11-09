package uk.iqama.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads today's times from the mosque's public Masjidbox page. The page draws its timetable with
 * JavaScript, so it is loaded in an off-screen WebView and its visible text is parsed.
 * Same approach as the TV plugin.
 */
object Fetcher {
    private const val TAG = "IqamaWidgetFetch"
    private const val TIMEOUT_MS = 45_000L
    private const val POLL_MS = 1_000L
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())

    fun isStale(prefs: Prefs): Boolean =
        prefs.fetchedDay != today() ||
            System.currentTimeMillis() - prefs.fetchedAt > MAX_AGE_MS ||
            PrayerTimes.fromJson(prefs.timesJson).isEmpty()

    /** Fetches on the main thread; [onDone] runs on the main thread with true if times were saved. */
    fun fetch(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        main.post {
            try {
                load(app, Prefs(app), onDone)
            } catch (e: Throwable) {
                Log.e(TAG, "WebView unavailable", e)
                Prefs(app).lastError = "WebView unavailable"
                onDone(false)
            }
        }
    }

    /** Blocking version for background work. Never call on the main thread. */
    fun fetchBlocking(context: Context): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        fetch(context) { ok -> result = ok; latch.countDown() }
        latch.await(TIMEOUT_MS + 20_000L, TimeUnit.MILLISECONDS)
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(context: Context, prefs: Prefs, onDone: (Boolean) -> Unit) {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.blockNetworkImage = true
        web.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, 1280, 720)
        web.resumeTimers()
        web.onResume()

        val started = System.currentTimeMillis()
        var finished = false

        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(web)
            try {
                web.stopLoading()
                web.destroy()
            } catch (_: Throwable) {
            }
            onDone(ok)
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished) return@Runnable
            web.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { raw ->
                if (finished) return@evaluateJavascript
                val text = decode(raw)
                val prayers = PrayerTimes.parse(text)
                if (PrayerTimes.isUsable(prayers)) {
                    prefs.timesJson = PrayerTimes.toJson(prayers)
                    prefs.pageSnippet = PrayerTimes.snippet(text)
                    prefs.fetchedAt = System.currentTimeMillis()
                    prefs.fetchedDay = today()
                    prefs.lastError = ""
                    finish(true)
                } else if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                    prefs.lastError = if (text.contains("404") || text.contains("not found", true))
                        "Mosque page not found" else "Times didn't appear on the page"
                    finish(false)
                } else {
                    main.postAtTime(poll, web, SystemClock.uptimeMillis() + POLL_MS)
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                main.removeCallbacksAndMessages(web)
                main.postAtTime(poll, web, SystemClock.uptimeMillis() + POLL_MS)
            }
        }

        main.postDelayed({
            if (!finished) {
                prefs.lastError = "Masjidbox didn't respond"
                finish(false)
            }
        }, TIMEOUT_MS + 10_000L)

        web.loadUrl(prefs.mosqueUrl, mapOf("Accept-Language" to "en-GB,en;q=0.9"))
    }

    private fun decode(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            JSONArray("[$raw]").getString(0)
        } catch (_: Exception) {
            raw
        }
    }
}
