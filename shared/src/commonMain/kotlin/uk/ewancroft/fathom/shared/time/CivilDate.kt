package uk.ewancroft.fathom.shared.time

import uk.ewancroft.fathom.shared.math.floorDiv
import uk.ewancroft.fathom.shared.math.floorMod

/**
 * A calendar date with no time or zone.
 *
 * Fathom needs calendar days for streaks and the listening bar chart, and
 * needs to bucket a play into the day the listener experienced rather than
 * the UTC day its record happens to fall in. That is the only reason this
 * exists: `kotlinx-datetime` would be a whole dependency for two arithmetic
 * routines, and neither routine needs a database of leap seconds.
 */
data class CivilDate(val year: Int, val month: Int, val day: Int) {

    /** The `YYYY-MM-DD` form used as a stable map key and in stored state. */
    fun iso(): String = buildString {
        append(pad(year, 4)); append('-'); append(pad(month, 2)); append('-'); append(pad(day, 2))
    }

    /** Days since 1970-01-01. 1970-01-01 itself is 0. */
    fun epochDay(): Long = daysFromCivil(year, month, day)

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    companion object {
        /**
         * Howard Hinnant's `days_from_civil`, which is exact for every
         * proleptic Gregorian date and avoids marching a year at a time.
         */
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
        }

        /** The inverse of [daysFromCivil], `civil_from_days`. */
        fun fromEpochDay(epochDay: Long): CivilDate {
            val z = epochDay + 719_468L
            val era = (if (z >= 0) z else z - 146_096L) / 146_097L
            val dayOfEra = z - era * 146_097L
            val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
            val year = yearOfEra + era * 400L
            val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
            val monthPrime = (5L * dayOfYear + 2L) / 153L
            val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
            val month = if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L
            return CivilDate(
                year = (if (month <= 2L) year + 1L else year).toInt(),
                month = month.toInt(),
                day = day.toInt(),
            )
        }
    }
}

/**
 * Calendar helpers over epoch seconds, always through a caller-supplied
 * offset from UTC in minutes.
 *
 * A device's offset is passed in rather than read here so that every
 * function stays pure and host-testable, and so a play can be bucketed
 * with the offset that applied where the listener was.
 */
object CivilDays {

    const val SECONDS_PER_DAY: Long = 86_400L

    /** The epoch day that [epochSeconds] falls in, at [tzOffsetMinutes] east of UTC. */
    fun epochDay(epochSeconds: Long, tzOffsetMinutes: Int): Long {
        val shifted = epochSeconds + tzOffsetMinutes.toLong() * 60L
        return floorDiv(shifted, SECONDS_PER_DAY)
    }

    /** `YYYY-MM-DD` for [epochSeconds] at [tzOffsetMinutes]. */
    fun key(epochSeconds: Long, tzOffsetMinutes: Int): String =
        CivilDate.fromEpochDay(epochDay(epochSeconds, tzOffsetMinutes)).iso()

    /** `YYYY-MM-DD` for a raw epoch day. */
    fun keyOf(epochDay: Long): String = CivilDate.fromEpochDay(epochDay).iso()

    /**
     * Day of week for an epoch day, Sunday being 0. 1970-01-01 was a
     * Thursday, which is index 4 in this scheme.
     */
    fun dayOfWeek(epochDay: Long): Int = floorMod(epochDay + 4L, 7L).toInt()

    /** A short weekday name, for list separators. */
    fun weekdayName(epochDay: Long): String = WEEKDAYS[dayOfWeek(epochDay)]

    private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
}
