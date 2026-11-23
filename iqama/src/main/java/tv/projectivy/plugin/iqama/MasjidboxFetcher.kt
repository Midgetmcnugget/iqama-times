package tv.projectivy.plugin.iqama

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads today's times from the mosque's public Masjidbox page.
 *
 * The page builds its timetable with JavaScript, so it is loaded in an off-screen WebView
 * (exactly as a browser would show it) and its visible text is parsed once the times appear.
 * This runs at most a few times a day.
 */
object MasjidboxFetcher {
    private const val TAG = "IqamaFetcher"
    private const val TIMEOUT_MS = 45_000L
    private const val POLL_MS = 1_000L
    private const val STUCK_MS = 90_000L

    @Volatile
    private var startedAt = 0L

    /** Refresh when the stored times are for another day or older than this. */
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())

    /** After a failed attempt, wait this long before trying again automatically. */
    private const val RETRY_AFTER_FAILURE_MS = 15 * 60 * 1000L

    @Volatile
    private var lastFailureAt = 0L

    fun isStale(ignoreBackoff: Boolean = false): Boolean {
        val due = Prefs.fetchedDay != today() ||
            System.currentTimeMillis() - Prefs.fetchedAt > MAX_AGE_MS ||
            PrayerTimes.fromJson(Prefs.timesJson).isEmpty()
        return due && (ignoreBackoff || System.currentTimeMillis() - lastFailureAt > RETRY_AFTER_FAILURE_MS)
    }

    /**
     * Starts a refresh if one isn't already running. [onDone] runs on the main thread with
     * true if new times were saved.
     */
    fun refresh(context: Context, onDone: (Boolean) -> Unit = {}) {
        val start = synchronized(waiters) {
            waiters.add(onDone)
            // A fetch that has run far too long is treated as dead, so it can't block every
            // later refresh (that is what left the times stuck before).
            if (running.get() && System.currentTimeMillis() - startedAt > STUCK_MS) {
                Log.w(TAG, "Previous fetch never finished; starting again")
                running.set(false)
            }
            running.compareAndSet(false, true).also { if (it) startedAt = System.currentTimeMillis() }
        }
        if (!start) return  // already fetching; we'll be told when it ends
        val app = context.applicationContext
        val run = startedAt
        // Belt and braces: whatever happens inside, this fetch ends within STUCK_MS.
        main.postDelayed({
            if (running.get() && startedAt == run) {
                Prefs.lastError = "Took too long"
                complete(false)
            }
        }, STUCK_MS)
        main.post {
            try {
                load(app) { ok ->
                    if (startedAt != run) return@load  // a fetch given up on earlier
                    lastFailureAt = if (ok) 0L else System.currentTimeMillis()
                    // Times are saved: finish now. The optional background picture is
                    // downloaded separately so it can never hold the refresh up.
                    complete(ok)
                    if (ok) Thread {
                        try {
                            BackgroundImage.refresh(app)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Background picture failed", e)
                        }
                    }.start()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "WebView unavailable", e)
                Prefs.lastError = "WebView unavailable: ${e.message}"
                complete(false)
            }
        }
    }

    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** Ends the current fetch and tells everyone who asked for it. Main thread. */
    private fun complete(ok: Boolean) {
        val toNotify = synchronized(waiters) {
            if (!running.get() && waiters.isEmpty()) return
            running.set(false)
            waiters.toList().also { waiters.clear() }
        }
        toNotify.forEach { cb ->
            try { cb(ok) } catch (e: Throwable) { Log.w(TAG, "Callback failed", e) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(context: Context, onDone: (Boolean) -> Unit) {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.blockNetworkImage = true
        // Lay it out at a TV-like size so the page renders its full timetable.
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
                val text = decodeJsString(raw)
                val prayers = PrayerTimes.parse(text)
                if (PrayerTimes.isUsable(prayers)) {
                    Prefs.timesJson = PrayerTimes.toJson(prayers)
                    Prefs.pageSnippet = PrayerTimes.snippet(text)
                    Prefs.fetchedAt = System.currentTimeMillis()
                    Prefs.fetchedDay = today()
                    Prefs.lastError = ""
                    Log.i(TAG, "Read ${prayers.size} prayers")
                    finish(true)
                } else if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                    Prefs.lastError = if (text.contains("404") || text.contains("not found", true))
                        "Mosque page not found" else "Times didn't appear on the page"
                    Log.w(TAG, "Timed out; page text starts: ${text.take(200)}")
                    finish(false)
                } else {
                    main.postAtTime(poll, web, android.os.SystemClock.uptimeMillis() + POLL_MS)
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                main.removeCallbacksAndMessages(web)
                main.postAtTime(poll, web, android.os.SystemClock.uptimeMillis() + POLL_MS)
            }
        }

        // Hard stop in case the page never finishes loading.
        main.postDelayed({
            if (!finished) {
                Prefs.lastError = "Masjidbox didn't respond"
                finish(false)
            }
        }, TIMEOUT_MS + 10_000L)

        web.loadUrl(Prefs.mosqueUrl, mapOf("Accept-Language" to "en-GB,en;q=0.9"))
    }

    /** evaluateJavascript returns a JSON-encoded string; decode it. */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            JSONArray("[$raw]").getString(0)
        } catch (_: Exception) {
            raw
        }
    }
}
