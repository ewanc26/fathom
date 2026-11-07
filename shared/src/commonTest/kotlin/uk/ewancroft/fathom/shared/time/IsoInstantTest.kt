package uk.ewancroft.fathom.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoInstantTest {

    @Test
    fun parsesTheEpoch() {
        assertEquals(0L, IsoInstant.parse("1970-01-01T00:00:00Z"))
    }

    @Test
    fun parsesFractionalSeconds() {
        assertEquals(0L, IsoInstant.parse("1970-01-01T00:00:00.512Z"))
    }

    @Test
    fun parsesNumericOffsets() {
        // The same instant, written three ways.
        val utc = IsoInstant.parse("2026-11-07T17:02:12Z")
        assertEquals(utc, IsoInstant.parse("2026-11-07T18:02:12+01:00"))
        assertEquals(utc, IsoInstant.parse("2026-11-07T12:02:12-05:00"))
        assertEquals(1794070932L, utc)
    }

    @Test
    fun rejectsUnusableValues() {
        assertNull(IsoInstant.parse(null))
        assertNull(IsoInstant.parse(""))
        assertNull(IsoInstant.parse("2026-11-07"))
        assertNull(IsoInstant.parse("not-a-date"))
        assertNull(IsoInstant.parse("2026-13-01T00:00:00Z"))
        assertNull(IsoInstant.parse("2026-02-30T00:00:00Z"))
        assertNull(IsoInstant.parse("2026-11-07T25:00:00Z"))
        assertNull(IsoInstant.parse("2026-11-07T17:02:12+0100"))
    }

    @Test
    fun toleratesLeapSeconds() {
        assertEquals(
            IsoInstant.parse("2016-12-31T23:59:59Z"),
            IsoInstant.parse("2016-12-31T23:59:60Z"),
        )
    }

    @Test
    fun handlesLeapYears() {
        // 2028 is a leap year, so 29 February exists and 2027 has no 29th.
        assertEquals(1L, (IsoInstant.parse("2028-03-01T00:00:00Z")!! -
            IsoInstant.parse("2028-02-29T00:00:00Z")!!) / 86_400L)
        assertNull(IsoInstant.parse("2027-02-29T00:00:00Z"))
    }
}
