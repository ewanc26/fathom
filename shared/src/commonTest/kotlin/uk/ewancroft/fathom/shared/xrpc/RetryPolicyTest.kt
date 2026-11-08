package uk.ewancroft.fathom.shared.xrpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uk.ewancroft.fathom.shared.time.IsoInstant

class RetryPolicyTest {

    private val now = IsoInstant.parse("2026-09-20T00:00:00Z")!!

    @Test
    fun retriesRateLimitsAndServerFailures() {
        assertTrue(RetryPolicy.isRetryableStatus(429))
        assertTrue(RetryPolicy.isRetryableStatus(500))
        assertTrue(RetryPolicy.isRetryableStatus(503))
    }

    @Test
    fun doesNotRetryAbsenceOrRejectedRequests() {
        assertFalse(RetryPolicy.isRetryableStatus(400))
        assertFalse(RetryPolicy.isRetryableStatus(401))
        assertFalse(RetryPolicy.isRetryableStatus(404))
        assertFalse(RetryPolicy.isRetryableStatus(200))
    }

    @Test
    fun backsOffExponentiallyAndCaps() {
        assertEquals(500L, RetryPolicy.delayMillis(0, jitterMillis = 0L))
        assertEquals(1_000L, RetryPolicy.delayMillis(1, jitterMillis = 0L))
        assertEquals(2_000L, RetryPolicy.delayMillis(2, jitterMillis = 0L))
        assertEquals(RetryPolicy.MAX_DELAY_MILLIS, RetryPolicy.delayMillis(20, jitterMillis = 0L))
    }

    @Test
    fun honoursRetryAfterSeconds() {
        assertEquals(5_000L, RetryPolicy.delayMillis(0, "5", now, 0L))
        assertEquals(0L, RetryPolicy.delayMillis(0, "0", now, 0L))
        assertEquals(RetryPolicy.MAX_DELAY_MILLIS, RetryPolicy.delayMillis(0, "999", now, 0L))
    }

    @Test
    fun honoursRetryAfterHttpDate() {
        assertEquals(
            5_000L,
            RetryPolicy.delayMillis(0, "Sun, 20 Sep 2026 00:00:05 GMT", now, 0L),
        )
        assertEquals(
            0L,
            RetryPolicy.delayMillis(0, "Sun, 20 Sep 2026 00:00:00 GMT", now, 0L),
        )
    }

    @Test
    fun fallsBackToBackoffForAMalformedHeader() {
        assertEquals(
            500L,
            RetryPolicy.delayMillis(0, "not-a-date", now, 0L),
        )
    }
}
