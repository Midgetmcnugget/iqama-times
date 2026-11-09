package tv.projectivy.plugin.iqama

import org.json.JSONArray
import org.json.JSONObject

/**
 * One prayer's times, in minutes after midnight.
 * [key] is a normalised name: FAJR, SHURUQ, DHUHR, ASR, MAGHRIB, ISHA, JUMUAH1, JUMUAH2.
 */
data class Prayer(val key: String, val athan: Int?, val iqamah: Int?)

object PrayerTimes {

    /** The five daily prayers in display order. */
    val DAILY = listOf("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")

    fun label(key: String): String = when (key) {
        "FAJR" -> "Fajr"
        "SHURUQ" -> "Sunrise"
        "DHUHR" -> "Dhuhr"
        "ASR" -> "Asr"
        "MAGHRIB" -> "Maghrib"
        "ISHA" -> "Isha"
        "JUMUAH1", "JUMUAH2" -> "Jumu'ah"
        else -> key.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun normaliseName(line: String): String? {
        val s = line.trim().uppercase().replace("'", "").replace("’", "")
        return when {
            s == "FAJR" || s == "FAJAR" -> "FAJR"
            s == "SHURUQ" || s == "SUNRISE" || s == "SHOROUK" -> "SHURUQ"
            s == "DHUHR" || s == "ZUHR" || s == "ZOHAR" || s == "DHUHUR" -> "DHUHR"
            s == "ASR" -> "ASR"
            s == "MAGHRIB" -> "MAGHRIB"
            s == "ISHA" -> "ISHA"
            Regex("^JUM(U|M)?A?H?\\s*1?$").matches(s) -> "JUMUAH1"
            Regex("^JUM(U|M)?A?H?\\s*2$").matches(s) -> "JUMUAH2"
            else -> null
        }
    }

    private fun isLabel(line: String, vararg names: String): Boolean {
        val s = line.trim().uppercase()
        return names.any { s == it }
    }

    private val hhmm = Regex("^(\\d{1,2})[:.](\\d{2})$")
    private val num = Regex("^\\d{1,2}$")

    /**
     * Reads a time starting at [i]: either "13:00" on one line or "13" then "00" on two lines
     * (that is how the Masjidbox page lays out its big digits).
     * Returns minutes after midnight and the index after the time, or null.
     */
    private fun readTime(lines: List<String>, i: Int): Pair<Int, Int>? {
        if (i >= lines.size) return null
        hhmm.matchEntire(lines[i])?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            return if (h < 24 && min < 60) (h * 60 + min) to (i + 1) else null
        }
        if (i + 1 < lines.size && num.matches(lines[i]) && num.matches(lines[i + 1])) {
            val h = lines[i].toInt(); val min = lines[i + 1].toInt()
            if (h < 24 && min < 60) return (h * 60 + min) to (i + 2)
        }
        return null
    }

    /**
     * Parses the visible text of a Masjidbox "prayer times" page. Each prayer looks like:
     *   FAJR / 6 / 45 / ATHAN / 5 / 33      -> big number is the iqamah, labelled number the athan
     *   JUMUAH 1 / 11 / 55 / IQAMAH / 12 / 45 -> big number is the athan, labelled number the iqamah
     *   SHURUQ / 7 / 15                      -> a single time
     */
    fun parse(pageText: String): List<Prayer> {
        val lines = pageText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val found = LinkedHashMap<String, Prayer>()
        var i = 0
        while (i < lines.size) {
            val key = normaliseName(lines[i])
            if (key == null || found.containsKey(key)) { i++; continue }
            val first = readTime(lines, i + 1)
            if (first == null) { i++; continue }
            var next = first.second
            var athan: Int? = null
            var iqamah: Int? = null
            if (next < lines.size && isLabel(lines[next], "ATHAN", "ADHAN", "AZAN", "ATHAN TIME")) {
                val second = readTime(lines, next + 1)
                iqamah = first.first
                athan = second?.first
                if (second != null) next = second.second
            } else if (next < lines.size && isLabel(lines[next], "IQAMAH", "IQAMA", "JAMAAH", "JAMAT", "JAMAAT")) {
                val second = readTime(lines, next + 1)
                athan = first.first
                iqamah = second?.first
                if (second != null) next = second.second
            } else {
                // A single time (e.g. sunrise): treat it as the start time.
                athan = first.first
            }
            found[key] = Prayer(key, athan, iqamah)
            i = next
        }
        return found.values.toList()
    }

    /** True if the list has enough for a useful display. */
    fun isUsable(list: List<Prayer>): Boolean =
        DAILY.count { k -> list.any { it.key == k && (it.iqamah != null || it.athan != null) } } >= 4

    fun toJson(list: List<Prayer>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("k", p.key)
                p.athan?.let { put("a", it) }
                p.iqamah?.let { put("i", it) }
            })
        }
    }.toString()

    fun fromJson(json: String): List<Prayer> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                Prayer(
                    o.getString("k"),
                    if (o.has("a")) o.getInt("a") else null,
                    if (o.has("i")) o.getInt("i") else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun format(minutes: Int, twelveHour: Boolean): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return if (twelveHour) {
            val h12 = if (h % 12 == 0) 12 else h % 12
            "%d:%02d".format(h12, m)
        } else {
            "%02d:%02d".format(h, m)
        }
    }
}
