package uk.iqama.widget

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Reads a mosque's times from its public Mawaqit page (mawaqit.net/en/<mosque>).
 * The page carries the whole year's timetable as data ("confData"):
 *  - calendar: 12 months, each day -> [fajr, shuruq, dhuhr, asr, maghrib, isha] adhan times;
 *  - iqamaCalendar: 12 months, each day -> [fajr, dhuhr, asr, maghrib, isha] iqamah, written
 *    either as minutes after adhan ("+15") or as a time ("13:30");
 *  - jumua (and jumua2): Friday prayer time; name: the mosque's name.
 * No hidden browser is needed: the page is read as plain text.
 */
object Mawaqit {
    const val PREFIX = "mawaqit:"
    private const val MAX_BYTES = 6 * 1024 * 1024

    fun isMawaqit(key: String) = key.startsWith(PREFIX)

    /** "mawaqit:my-mosque" -> "my-mosque". */
    fun slugOf(key: String) = key.removePrefix(PREFIX)

    fun pageUrl(key: String) = "https://mawaqit.net/en/${slugOf(key)}"

    data class Result(val name: String?, val prayers: List<Prayer>)

    /** One download, two days: [day] (must work) and [next] (null if the timetable lacks it). */
    fun fetchDays(key: String, day: Calendar, next: Calendar): Pair<Result, Result?> {
        val html = download(pageUrl(key))
        val json = JSONObject(extractConfData(html) ?: error("Times not found on the Mawaqit page"))
        val first = parse(json, day)
        val second = try { parse(json, next) } catch (_: Throwable) { null }
        return first to second
    }

    /** Downloads and reads today's times. Blocks: call off the main thread. Throws with a short reason on failure. */
    fun fetch(key: String, today: Calendar = Calendar.getInstance()): Result {
        val html = download(pageUrl(key))
        val json = extractConfData(html) ?: error("Times not found on the Mawaqit page")
        return parse(JSONObject(json), today)
    }

    fun download(address: String, json: Boolean = false): String {
        var url = URL(address)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IqamaTimesWidget")
            conn.setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            if (json) {
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Referer", "https://mawaqit.net/en/")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, loc)
                redirects++
                continue
            }
            if (code == 404) { conn.disconnect(); error("Mosque page not found on Mawaqit") }
            if (code != 200) { conn.disconnect(); error("Mawaqit replied $code") }
            break
        }
        val out = ByteArrayOutputStream()
        val input = conn.inputStream
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) break
            }
        } finally {
            input.close()
            conn.disconnect()
        }
        return out.toString("UTF-8")
    }

    /**
     * Finds `confData = {...}` in the page and returns the JSON object, matching braces so that
     * semicolons or braces inside text (e.g. announcements) don't cut it short.
     */
    fun extractConfData(html: String): String? {
        val m = Regex("""(?:var|let|const)\s+confData\s*=\s*\{""").find(html) ?: return null
        val start = m.range.last  // the opening brace
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return html.substring(start, i + 1) }
                }
            }
            i++
        }
        return null
    }

    /** Turns confData into today's prayers (the same shape the Masjidbox reader produces). */
    fun parse(conf: JSONObject, today: Calendar): Result {
        val month = today.get(Calendar.MONTH)            // 0-11
        val day = today.get(Calendar.DAY_OF_MONTH).toString()

        val adhan = dayEntry(conf.optJSONArray("calendar"), month, day)
            ?: error("No timetable for today on Mawaqit")
        if (adhan.length() < 6) error("Incomplete timetable on Mawaqit")
        fun a(i: Int) = toMinutes(adhan.optString(i))

        val iqamaOn = conf.optBoolean("iqamaEnabled", true)
        val iqama = if (iqamaOn) dayEntry(conf.optJSONArray("iqamaCalendar"), month, day) else null
        fun iq(i: Int, athan: Int?): Int? {
            val raw = iqama?.optString(i)?.trim().orEmpty()
            if (raw.isEmpty() || athan == null) return null
            if (raw.startsWith("+")) return raw.drop(1).toIntOrNull()?.let { athan + it }
            return toMinutes(raw)
        }

        val fajr = a(0); val shuruq = a(1); val dhuhr = a(2); val asr = a(3); val maghrib = a(4); val isha = a(5)
        val prayers = mutableListOf(
            Prayer("FAJR", fajr, iq(0, fajr)),
            Prayer("SHURUQ", shuruq, null),
            Prayer("DHUHR", dhuhr, iq(1, dhuhr)),
            Prayer("ASR", asr, iq(2, asr)),
            Prayer("MAGHRIB", maghrib, iq(3, maghrib)),
            Prayer("ISHA", isha, iq(4, isha)),
        )
        // Friday: Mawaqit gives the Jumu'ah prayer time; shown with Dhuhr's adhan.
        toMinutes(conf.optString("jumua"))?.let { prayers.add(Prayer("JUMUAH1", dhuhr, it)) }
        toMinutes(conf.optString("jumua2"))?.let { prayers.add(Prayer("JUMUAH2", dhuhr, it)) }

        val name = conf.optString("name").takeIf { it.isNotBlank() && it != "null" }
        return Result(name, prayers)
    }

    private fun dayEntry(calendar: JSONArray?, month: Int, day: String): JSONArray? {
        if (calendar == null || month >= calendar.length()) return null
        val m = calendar.optJSONObject(month) ?: return null
        return m.optJSONArray(day)
    }

    private fun toMinutes(s: String?): Int? {
        val t = s?.trim() ?: return null
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h < 24 && min < 60) h * 60 + min else null
    }
}
