package uk.ewancroft.fathom.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals

class CivilDaysTest {

    private val instant = IsoInstant.parse("2026-11-07T17:02:12Z")!!

    @Test
    fun keysTheUtcDay() {
        assertEquals("2026-11-07", CivilDays.key(instant, 0))
    }

    @Test
    fun bucketsByLocalMidnightRatherThanUtc() {
        val lateEvening = IsoInstant.parse("2026-11-07T23:30:00Z")!!
        val earlyMorning = IsoInstant.parse("2026-11-07T00:30:00Z")!!

        // East of Greenwich the evening belongs to the next local day.
        assertEquals("2026-11-08", CivilDays.key(lateEvening, 60))
        // West of Greenwich the small hours belong to the previous one.
        assertEquals("2026-11-06", CivilDays.key(earlyMorning, -60))
    }

    @Test
    fun negativeOffsetsDoNotTruncateTowardsZero() {
        // 00:30Z at -05:00 is 19:30 on the 6th. Floor division is what makes
        // this the 6th rather than the 7th.
        val earlyMorning = IsoInstant.parse("2026-11-07T00:30:00Z")!!
        assertEquals("2026-11-06", CivilDays.key(earlyMorning, -300))
    }

    @Test
    fun roundTripsCalendarDates() {
        for (epochDay in listOf(0L, 20_454L, 20_764L, 21_000L)) {
            val date = CivilDate.fromEpochDay(epochDay)
            assertEquals(epochDay, date.epochDay(), "round trip for $epochDay")
        }
        assertEquals("1970-01-01", CivilDate.fromEpochDay(0L).iso())
        assertEquals("2026-11-07", CivilDate.fromEpochDay(20_764L).iso())
    }

    @Test
    fun namesTheRightWeekday() {
        // The epoch was a Thursday.
        assertEquals("Thu", CivilDays.weekdayName(0L))
        assertEquals("Sat", CivilDays.weekdayName(20_764L))
    }

    @Test
    fun formatsPaddedKeys() {
        assertEquals("2026-01-05", CivilDate(2026, 1, 5).iso())
    }
}
