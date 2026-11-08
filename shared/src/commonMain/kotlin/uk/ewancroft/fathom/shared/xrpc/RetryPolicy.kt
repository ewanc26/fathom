package uk.ewancroft.fathom.shared.xrpc

import kotlin.random.Random
import uk.ewancroft.fathom.shared.time.IsoInstant

/**
 * Bounded retry timing for idempotent reads.
 *
 * A full history is hundreds of sequential GETs, so a 429 or a transient 5xx
 * is part of a long read rather than a reason to abandon it. The policy is
 * shared so Android and iOS wait for the same amount of time and honour the
 * same headers; only the HTTP clients themselves stay native.
 *
 * `Retry-After` is honoured in both forms the HTTP specification allows:
 * delay-seconds and an IMF-fixdate. A malformed or missing header falls back
 * to exponential backoff with jitter, and every wait is capped so a broken
 * server cannot hold a dashboard forever.
 */
object RetryPolicy {

    /** One request plus three retries. */
    const val MAX_ATTEMPTS = 4

    const val BASE_DELAY_MILLIS = 500L

    /** Long enough to respect a rate limit, short enough to stay interactive. */
    const val MAX_DELAY_MILLIS = 15_000L

    /** 429 and server-side failures are worth another request; 4xx is not. */
    fun isRetryableStatus(status: Int): Boolean =
        status == 429 || status in 500..599

    /**
     * The wait before the next attempt.
     *
     * [retryAfterHeader] wins when it is valid. [nowEpochSeconds] is required
     * to turn an HTTP-date into a delay; callers pass the same clock they use
     * for the rest of the read. [jitterMillis] exists for tests, which need a
     * deterministic wait; production callers leave it null.
     */
    fun delayMillis(
        attempt: Int,
        retryAfterHeader: String? = null,
        nowEpochSeconds: Long = 0L,
        jitterMillis: Long? = null,
    ): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        if (seconds != null && seconds >= 0) {
            return (seconds * 1_000L).coerceAtMost(MAX_DELAY_MILLIS)
        }

        val date = retryAfterHeader
            ?.takeIf { it.isNotBlank() }
            ?.let(::httpDateEpochSeconds)
        if (date != null) {
            val secondsUntil = (date - nowEpochSeconds).coerceAtLeast(0L)
            return (secondsUntil * 1_000L).coerceAtMost(MAX_DELAY_MILLIS)
        }

        val step = attempt.coerceIn(0, 5)
        val base = (BASE_DELAY_MILLIS shl step).coerceAtMost(MAX_DELAY_MILLIS)
        val jitter = jitterMillis
            ?: Random.nextLong(0, (base / 4).coerceAtLeast(1L) + 1L)
        return (base + jitter).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /**
     * `Sun, 20 Sep 2026 00:00:05 GMT` as an epoch second.
     *
     * Parsing the date here, rather than in each transport, keeps the two
     * platforms on one implementation and lets the shared test suite cover
     * the format. The weekday is irrelevant; the date and time are not.
     */
    private fun httpDateEpochSeconds(value: String): Long? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 6) return null
        if (!parts[5].equals("GMT", ignoreCase = true) &&
            !parts[5].equals("UTC", ignoreCase = true)
        ) {
            return null
        }

        val day = parts[1].toIntOrNull() ?: return null
        val month = MONTHS[parts[2].lowercase()] ?: return null
        val year = parts[3].toIntOrNull() ?: return null
        val time = parts[4].split(":")
        if (time.size != 3) return null

        val hour = time[0].toIntOrNull() ?: return null
        val minute = time[1].toIntOrNull() ?: return null
        val second = time[2].toIntOrNull() ?: return null
        if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }

        return IsoInstant.parse(
            year.toString().padStart(4, '0') + "-" +
                month.toString().padStart(2, '0') + "-" +
                day.toString().padStart(2, '0') + "T" +
                hour.toString().padStart(2, '0') + ":" +
                minute.toString().padStart(2, '0') + ":" +
                second.toString().padStart(2, '0') + "Z",
        )
    }

    private val MONTHS = mapOf(
        "jan" to 1,
        "feb" to 2,
        "mar" to 3,
        "apr" to 4,
        "may" to 5,
        "jun" to 6,
        "jul" to 7,
        "aug" to 8,
        "sep" to 9,
        "oct" to 10,
        "nov" to 11,
        "dec" to 12,
    )
}
