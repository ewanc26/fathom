package uk.ewancroft.fathom.shared.time

/**
 * Parsing for the `format: datetime` strings AT Protocol records carry.
 *
 * The wire format is RFC 3339: `2026-11-07T17:02:12Z`, optionally with
 * fractional seconds and optionally with a numeric offset instead of `Z`.
 * A PDS is free to store either, and a record written by another client can
 * carry any of the forms, so both are accepted. Anything malformed returns
 * null rather than throwing: one unreadable timestamp must not cost the
 * caller a whole page of plays.
 */
object IsoInstant {

    /** Parses [raw] to epoch seconds, truncating fractions. Null when unreadable. */
    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val separator = text.indexOf('T')
        if (separator <= 0) return null

        val date = parseDate(text.substring(0, separator)) ?: return null
        val remainder = text.substring(separator + 1)
        if (remainder.isEmpty()) return null

        val offsetSeconds: Int
        val timePart: String
        if (remainder.endsWith("Z") || remainder.endsWith("z")) {
            offsetSeconds = 0
            timePart = remainder.dropLast(1)
        } else {
            val signAt = remainder.indexOfLast { it == '+' || it == '-' }
            if (signAt <= 0) return null
            val offset = remainder.substring(signAt + 1)
            if (offset.length != 5 || offset[2] != ':') return null
            val hours = offset.substring(0, 2).toIntOrNull() ?: return null
            val minutes = offset.substring(3, 5).toIntOrNull() ?: return null
            if (hours > 23 || minutes > 59) return null
            val sign = if (remainder[signAt] == '-') -1 else 1
            offsetSeconds = sign * (hours * 3600 + minutes * 60)
            timePart = remainder.substring(0, signAt)
        }

        val clock = parseTime(timePart) ?: return null
        val epochDay = CivilDate.daysFromCivil(date.year, date.month, date.day)
        return epochDay * CivilDays.SECONDS_PER_DAY +
            clock.toLong() -
            offsetSeconds.toLong()
    }

    private fun parseDate(text: String): CivilDate? {
        val pieces = text.split('-')
        if (pieces.size != 3) return null
        if (pieces[0].length != 4) return null
        val year = pieces[0].toIntOrNull() ?: return null
        val month = pieces[1].toIntOrNull() ?: return null
        val day = pieces[2].toIntOrNull() ?: return null
        if (year < 1900 || year > 2200) return null
        if (month !in 1..12) return null
        if (day !in 1..daysInMonth(year, month)) return null
        return CivilDate(year, month, day)
    }

    /** Seconds within the day, fraction truncated. */
    private fun parseTime(text: String): Int? {
        val pieces = text.split(':')
        if (pieces.size != 3) return null
        if (pieces[0].length != 2 || pieces[1].length != 2) return null
        val hour = pieces[0].toIntOrNull() ?: return null
        val minute = pieces[1].toIntOrNull() ?: return null
        val secondField = pieces[2].substringBefore('.')
        if (secondField.length != 2) return null
        val second = secondField.toIntOrNull() ?: return null
        // 23:59:60 is a real leap second and falls inside 23:59:59, so it is
        // clamped rather than rejected. Adding it would place the instant one
        // second into the next day.
        if (hour > 23 || minute > 59 || second > 60) return null
        return hour * 3600 + minute * 60 + second.coerceAtMost(59)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeapYear(year)) 29 else 28
    }

    fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
