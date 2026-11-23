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
            s == "MAGHRIB" || s == "MAGRIB" -> "MAGHRIB"
            s == "ISHA" -> "ISHA"
            Regex("^JUM(U|M)?A?H?\\s*1?$").matches(s) -> "JUMUAH1"
            Regex("^JUM(U|M)?A?H?\\s*2$").matches(s) -> "JUMUAH2"
            else -> null
        }
    }

    private val ATHAN_WORDS = listOf("ATHAN TIME", "ATHAN", "ADHAN", "AZAN", "BEGINS", "START", "STARTS")
    private val IQAMAH_WORDS = listOf("IQAMAH", "IQAMA", "IQAMAT", "JAMAAH", "JAMAAT", "JAMAT", "JAMAH", "CONGREGATION")

    private enum class Kind { ATHAN, IQAMAH }

    /**
     * If [line] is an athan/iqamah label, returns its kind and whatever follows it on the same
     * line (e.g. "IQAMAH +15" -> IQAMAH, "+15"). Otherwise null.
     */
    private fun labelOf(line: String): Pair<Kind, String>? {
        val s = line.trim().uppercase().replace(":", " ").trim()
        for ((words, kind) in listOf(ATHAN_WORDS to Kind.ATHAN, IQAMAH_WORDS to Kind.IQAMAH)) {
            for (w in words) {
                if (s == w) return kind to ""
                if (s.startsWith("$w ")) return kind to line.trim().substring(w.length).trim().trimStart(':').trim()
                // "Iqamah6:45" / "Iqamah+15": no space between the label and its value.
                if (s.length > w.length && s.startsWith(w) && (s[w.length].isDigit() || s[w.length] == '+'))
                    return kind to line.trim().substring(w.length).trim()
            }
        }
        return null
    }

    private val hhmm = Regex("^(\\d{1,2})[:.](\\d{2})\\s*([AaPp][Mm])?$")
    private val num = Regex("^\\d{1,2}$")
    private val twoDigits = Regex("^\\d{2}$")
    /** Iqamah given as minutes after the athan: "+15", "+ 15 min", "15 mins after". */
    private val offset = Regex("^\\+\\s*(\\d{1,3})\\s*(M|MIN|MINS|MINUTES|')?(\\s*AFTER)?$|^(\\d{1,3})\\s*(M|MIN|MINS|MINUTES|')(\\s*AFTER)?$", RegexOption.IGNORE_CASE)
    private val dash = Regex("^[-–—]{1,3}$")

    private fun parseHhmm(text: String): Int? {
        val m = hhmm.matchEntire(text.trim()) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].uppercase()
        if (ampm == "PM" && h < 12) h += 12
        if (ampm == "AM" && h == 12) h = 0
        return if (h < 24 && min < 60) h * 60 + min else null
    }

    /**
     * Reads a time starting at [i]: "13:00" (or "1:00 PM") on one line, or "13" then "00" on two
     * lines (how the Masjidbox page lays out its big digits).
     * Returns minutes after midnight and the index after the time, or null.
     */
    private fun readTime(lines: List<String>, i: Int): Pair<Int, Int>? {
        if (i >= lines.size) return null
        parseHhmm(lines[i])?.let { return it to (i + 1) }
        if (i + 1 < lines.size && num.matches(lines[i]) && num.matches(lines[i + 1])) {
            val h = lines[i].toInt(); val min = lines[i + 1].toInt()
            if (h < 24 && min < 60) return (h * 60 + min) to (i + 2)
        }
        return null
    }

    /** A value after a label: a clock time, a "+15"-style offset, or "--" (none). */
    private sealed class Value {
        data class Time(val minutes: Int) : Value()
        data class Offset(val minutes: Int) : Value()
        object None : Value()
    }

    private fun parseValue(text: String): Value? {
        val t = text.trim()
        if (t.isEmpty()) return null
        parseHhmm(t)?.let { return Value.Time(it) }
        offset.matchEntire(t)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toInt()
            return Value.Offset(n)
        }
        if (dash.matches(t)) return Value.None
        return null
    }

    /** Reads a value from the same-line remainder [rest], or else from lines starting at [i]. */
    private fun readValue(lines: List<String>, i: Int, rest: String): Pair<Value, Int>? {
        if (rest.isNotEmpty()) return parseValue(rest)?.let { it to i }
        readTime(lines, i)?.let { return Value.Time(it.first) to it.second }
        if (i < lines.size) parseValue(lines[i])?.let { return it to (i + 1) }
        return null
    }

    /**
     * For pages laid out as a table with "ATHAN  IQAMAH" column headers, works out which
     * column comes first. Default: athan first.
     */
    private fun iqamahColumnFirst(lines: List<String>): Boolean {
        for (j in lines.indices) {
            val a = labelOf(lines[j]) ?: continue
            if (a.second.isNotEmpty()) continue
            for (k in j + 1..minOf(j + 2, lines.size - 1)) {
                val b = labelOf(lines[k]) ?: continue
                if (b.second.isEmpty() && b.first != a.first) return a.first == Kind.IQAMAH
            }
        }
        return false
    }

    /**
     * Parses the visible text of a Masjidbox "prayer times" page. Handles the layouts seen so far:
     *   FAJR / 6 / 45 / ATHAN / 5 / 33        -> big number is the iqamah, labelled one the athan
     *   JUMUAH 1 / 11 / 55 / IQAMAH / 12 / 45 -> big number is the athan, labelled one the iqamah
     *   FAJR / 06:30 / IQAMAH / +15           -> iqamah given as minutes after the athan
     *   FAJR / 06:30 / IQAMAH 06:45           -> label and time on one line
     *   Fajr / 06:30 / 06:45                  -> table with ATHAN / IQAMAH column headers
     *   SHURUQ / 7 / 15  or  Shuruq / 07:08 / --  -> a single time
     */
    /**
     * Evens out layouts the page has used: a time split over lines ("5" / ":" / "46"),
     * and a name or label with its time on the same line ("Fajr 5:46", "Iqamah6:45").
     */
    private fun tidy(raw: List<String>): List<String> {
        val joined = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            val b = raw.getOrNull(i + 1)
            val c = raw.getOrNull(i + 2)
            when {
                num.matches(a) && b != null && (b == ":" || b == ".") && c != null && twoDigits.matches(c) -> {
                    joined += "$a:$c"; i += 3
                }
                Regex("^\\d{1,2}[:.]$").matches(a) && b != null && twoDigits.matches(b) -> {
                    joined += a.dropLast(1) + ":" + b; i += 2
                }
                num.matches(a) && b != null && Regex("^[:.]\\d{2}$").matches(b) -> {
                    joined += a + ":" + b.drop(1); i += 2
                }
                // "5" / "18 PM" or "18PM" (12-hour pages)
                num.matches(a) && b != null && Regex("^\\d{2}\\s*[AaPp][Mm]$").matches(b) -> {
                    joined += a + ":" + b.take(2) + " " + b.drop(2).trim().uppercase(); i += 2
                }
                else -> { joined += a; i++ }
            }
        }
        // "5:18" / "PM" -> "5:18 PM"
        val withAmPm = mutableListOf<String>()
        for (line in joined) {
            val last = withAmPm.lastOrNull()
            if (last != null && Regex("^[AaPp][Mm]$").matches(line) && Regex("^\\d{1,2}[:.]\\d{2}$").matches(last))
                withAmPm[withAmPm.size - 1] = "$last ${line.uppercase()}"
            else withAmPm += line
        }
        val out = mutableListOf<String>()
        for (line in withAmPm) splitLine(line, out)
        return out
    }

    private val nameThenTime = Regex("^(.*?[\\p{L}'’])\\s*(\\d{1,2}[:.]\\d{2}(?:\\s*[AaPp][Mm])?)\\s*(.*)$")

    /** "Fajr 5:46 Iqamah 6:45" -> "Fajr", "5:46", "Iqamah", "6:45" (only for known names and labels). */
    private fun splitLine(line: String, out: MutableList<String>) {
        if (parseHhmm(line) != null) { out += line; return }
        val m = nameThenTime.matchEntire(line)
        val head = m?.groupValues?.get(1)?.trim()
        if (m == null || head == null || (normaliseName(head) == null && labelOf(head) == null)) { out += line; return }
        out += head
        out += m.groupValues[2].trim()
        val rest = m.groupValues[3].trim()
        if (rest.isNotEmpty()) splitLine(rest, out)
    }

    fun parse(pageText: String): List<Prayer> {
        val lines = tidy(pageText.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val iqamahFirst = iqamahColumnFirst(lines)
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
            val lab = if (next < lines.size) labelOf(lines[next]) else null
            if (lab != null) {
                val v = readValue(lines, next + 1, lab.second)
                if (v != null) next = v.second else next += 1
                val value = v?.first
                if (lab.first == Kind.ATHAN) {
                    // Big number is the iqamah, labelled one the athan.
                    iqamah = first.first
                    athan = (value as? Value.Time)?.minutes
                    if (value is Value.Offset) athan = first.first - value.minutes
                } else {
                    // Big number is the athan, labelled one the iqamah.
                    athan = first.first
                    iqamah = when (value) {
                        is Value.Time -> value.minutes
                        is Value.Offset -> first.first + value.minutes
                        else -> null
                    }
                }
            } else {
                // Table layout: a second value straight after the first, or a single time.
                val second = if (next < lines.size && normaliseName(lines[next]) == null)
                    readTime(lines, next)?.let { Value.Time(it.first) to it.second }
                        ?: parseValue(lines[next])?.takeIf { it !is Value.Time }?.let { it to next + 1 }
                else null
                if (second != null) {
                    next = second.second
                    val other = when (val v = second.first) {
                        is Value.Time -> v.minutes
                        is Value.Offset -> first.first + v.minutes
                        else -> null
                    }
                    if (iqamahFirst) { iqamah = first.first; athan = other }
                    else { athan = first.first; iqamah = other }
                } else {
                    // Only one time (e.g. sunrise), and the page doesn't say which it is.
                    athan = first.first
                }
            }
            found[key] = Prayer(key, athan, iqamah)
            i = next
        }
        return found.values.toList()
    }

    /** The timetable part of the page text (from the first prayer name), for troubleshooting. */
    fun snippet(pageText: String, maxLines: Int = 45): String {
        val lines = pageText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOfFirst { normaliseName(it) != null }.let { if (it < 0) 0 else maxOf(0, it - 4) }
        return lines.drop(start).take(maxLines).joinToString(" · ")
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
