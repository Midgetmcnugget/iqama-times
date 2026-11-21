package uk.iqama.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Finds a mosque from a name typed in, on Mawaqit and Masjidbox, and ranks the matches so the
 * best guess can be picked automatically.
 *  - Mawaqit: its public mosque search (the one the mawaqit.net website uses). If that answers
 *    with nothing, the name is tried as a page address (mawaqit.net/en/<name-with-dashes>).
 *  - Masjidbox: has no public search, so the name is tried as a page address
 *    (masjidbox.com/prayer-times/<name-with-dashes>), which is how its addresses are made.
 */
object MosqueSearch {

    data class Hit(val key: String, val name: String, val place: String, val source: String, val score: Double)

    /** Words that say "mosque" rather than which one: they count for little when matching. */
    private val GENERIC = setOf(
        "masjid", "masjed", "masjide", "mosque", "mosquee", "moschee", "jamia", "jamiah", "jame", "jamea",
        "jami", "jamie", "markaz", "musalla", "musallah", "the", "of", "and", "centre", "center", "islamic",
        "community", "trust", "association", "al", "el", "ul", "e"
    )

    fun tokens(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ').filter { it.isNotBlank() }

    /** Latin letters and digits joined with dashes, as page addresses are written. */
    fun slugify(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun close(a: String, b: String) =
        a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) ||
            (a.length >= 5 && b.length >= 5 && editDistance(a, b) <= 1)

    /** 0..1-ish: how well a mosque's name and address match what was typed. */
    fun score(query: String, name: String, place: String): Double {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return 0.0
        val significant = q.filter { it !in GENERIC }.ifEmpty { q }
        val nameWords = tokens(name)
        val allWords = nameWords + tokens(place)
        val inAll = significant.count { t -> allWords.any { close(it, t) } }.toDouble() / significant.size
        val inName = significant.count { t -> nameWords.any { close(it, t) } }.toDouble() / significant.size
        // Small extras: "masjid" typed and "masjid" in the name; few extra words in the name.
        val generic = q.filter { it in GENERIC && it in nameWords }.size * 0.02
        val extra = (nameWords.filter { it !in GENERIC }.size - significant.size).coerceAtLeast(0) * 0.01
        return inAll * 0.6 + inName * 0.4 + generic - extra
    }

    // ---------------- Mawaqit ----------------

    /** Blocking: call off the main thread. Throws only if Mawaqit couldn't be reached at all. */
    fun searchMawaqit(query: String): List<Hit> {
        val words = tokens(query)
        val significant = words.filter { it !in GENERIC }
        val attempts = listOf(words.joinToString(" "), significant.joinToString(" "))
            .map { it.take(30).trim() }.filter { it.length >= 2 }.distinct()
        var lastError: Throwable? = null
        for (word in attempts) {
            try {
                val hits = parseMawaqitSearch(
                    Mawaqit.download(
                        "https://mawaqit.net/api/2.0/mosque/search?word=" + URLEncoder.encode(word, "UTF-8"),
                        json = true
                    ), query
                )
                if (hits.isNotEmpty()) return hits
            } catch (e: Throwable) {
                lastError = e
            }
        }
        // No search answer: try the name as a page address.
        for (slug in listOf(slugify(query), slugify(significant.joinToString(" "))).filter { it.length >= 3 }.distinct()) {
            try {
                val r = Mawaqit.fetch(Mawaqit.PREFIX + slug)
                if (PrayerTimes.isUsable(r.prayers)) {
                    val name = r.name ?: Prefs.titleFromSlug(slug)
                    return listOf(Hit(Mawaqit.PREFIX + slug, name, "", "Mawaqit", score(query, name, slug) + 0.05))
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (lastError != null && lastError.message?.contains("not found", true) != true) throw lastError
        return emptyList()
    }

    /** The search answers with a list of mosques (or, in some versions, an object holding one). */
    fun parseMawaqitSearch(body: String, query: String): List<Hit> {
        val text = body.trim()
        val list: JSONArray = when {
            text.startsWith("[") -> JSONArray(text)
            text.startsWith("{") -> JSONObject(text).let { o ->
                o.optJSONArray("data") ?: o.optJSONArray("mosques") ?: o.optJSONArray("results") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val hits = mutableListOf<Hit>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val slug = o.optString("slug").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (o.optString("closed").let { it.isNotBlank() && it != "null" }) continue
            val name = listOf(o.optString("label"), o.optString("name")).firstOrNull { it.isNotBlank() && it != "null" }
                ?: Prefs.titleFromSlug(slug)
            val place = o.optString("localisation").takeIf { it != "null" } ?: ""
            // Keep Mawaqit's own order as a tie-breaker.
            hits += Hit(Mawaqit.PREFIX + slug.lowercase(), name, place, "Mawaqit", score(query, name, place) - i * 0.001)
        }
        return hits
    }

    // ---------------- Masjidbox ----------------

    /** Addresses to try on Masjidbox for a typed name, most likely first. */
    fun masjidboxGuesses(query: String): List<String> {
        val base = slugify(query)
        if (base.length < 3) return emptyList()
        val words = base.split('-')
        val swapped = words.map {
            when (it) { "mosque" -> "masjid"; "masjid" -> "mosque"; else -> it }
        }.joinToString("-")
        val withMasjid = if (words.none { it == "masjid" || it == "mosque" }) "masjid-$base" else base
        return listOf(base, swapped, withMasjid).distinct()
    }

    /** Tries the guesses at the same time in hidden browsers; [onDone] gets the likeliest one that has times. */
    fun probeMasjidbox(context: Context, query: String, onDone: (Hit?) -> Unit) {
        val guesses = masjidboxGuesses(query)
        if (guesses.isEmpty()) { onDone(null); return }
        val prefs = Prefs(context)
        val found = BooleanArray(guesses.size)
        var left = guesses.size
        guesses.forEachIndexed { i, slug ->
            Fetcher.fetch(context, slug, probe = true) { ok ->
                found[i] = ok
                left--
                if (left == 0) {
                    val i2 = found.indexOfFirst { it }
                    if (i2 < 0) onDone(null) else {
                        val s = guesses[i2]
                        val name = prefs.mosque(s).name
                        onDone(Hit(s, name, "", "Masjidbox", maxOf(score(query, name, s), score(query, s, "")) + 0.05))
                    }
                }
            }
        }
    }

    /** Recently used mosques whose names match, so they're offered too. */
    fun recentHits(prefs: Prefs, query: String): List<Hit> =
        prefs.recentMosques.map { key ->
            val m = prefs.mosque(key)
            Hit(key, m.name, "", if (m.isMawaqit) "Mawaqit" else "Masjidbox", score(query, m.name, Mawaqit.slugOf(key)))
        }.filter { it.score >= 0.6 }

    /** Best first, one entry per mosque. */
    fun rank(hits: List<Hit>): List<Hit> =
        hits.groupBy { it.key }.map { (_, same) -> same.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
